"""Launch Dash Client directly with local Minecraft 1.21.11 and Java 25."""

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import winreg
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPOSITORY = "DashCEO/dash-client-private"
REPOSITORY_URL = f"https://github.com/{REPOSITORY}"
LATEST_API = f"https://api.github.com/repos/{REPOSITORY}/releases/latest"
LATEST_PAGE = f"{REPOSITORY_URL}/releases/latest"
MINECRAFT_VERSION = "1.21.11"
FABRIC_VERSION = "0.19.5"
MAVEN = "https://maven.fabricmc.net"
INSTALL_JAR_NAME = "dash-client-1.0.0.jar"
DOWNLOAD_HOSTS = {
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "maven.fabricmc.net",
    "cdn.modrinth.com",
}
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
RELEASE_MARKER = re.compile(
    r"DASH_RELEASE_ASSET\s+(dash-client-[0-9.]+\.jar)\s+([0-9]+)\s+([a-f0-9]{64})\b"
)
BUFFER_SIZE = 1024 * 1024
METADATA_LIMIT = 4 * BUFFER_SIZE
MAX_RELEASE_BYTES = 2 * 1024**3


class DashError(Exception):
    pass


class ReleaseRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        destination = urlsplit(new_url)
        if destination.scheme != "https" or destination.netloc not in DOWNLOAD_HOSTS:
            raise DashError("Сервер перенаправив запит на непідтримувану адресу.")
        return super().redirect_request(request, fp, code, message, headers, new_url)


OPEN_URL = build_opener(ReleaseRedirect()).open


@dataclass(frozen=True)
class Release:
    version: str
    size: int
    sha256: str

    @property
    def asset_name(self):
        return f"dash-client-{self.version}.jar"

    @property
    def url(self):
        return f"{REPOSITORY_URL}/releases/download/v{self.version}/{self.asset_name}"


def version_key(version):
    match = VERSION.fullmatch(version) if isinstance(version, str) else None
    if match is None:
        raise DashError("Очікується стабільна версія у форматі X.Y.Z.")
    return tuple(int(part) for part in match.groups())


def tag_version(tag):
    if not isinstance(tag, str) or not tag.startswith("v"):
        raise DashError("Реліз повинен мати тег vX.Y.Z.")
    version_key(tag[1:])
    return tag[1:]


def request_url(url, accept, timeout):
    headers = {"User-Agent": "DashClient-Idle/1.0", "Accept": accept}
    if url == LATEST_API:
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    return OPEN_URL(Request(url, headers=headers), timeout=timeout)


def metadata_body(response):
    data = response.read(METADATA_LIMIT + 1)
    if len(data) > METADATA_LIMIT:
        raise DashError("GitHub повернув завеликий опис релізу.")
    return data


def read_release_api():
    with request_url(LATEST_API, "application/vnd.github+json", 8) as response:
        payload = json.loads(metadata_body(response))
    if not isinstance(payload, dict) or payload.get("draft") is not False:
        raise DashError("GitHub не повернув опублікований реліз.")
    if payload.get("prerelease") is not False:
        raise DashError("Лоадер встановлює тільки стабільні релізи.")

    tag = payload.get("tag_name")
    version = tag_version(tag)
    assets = payload.get("assets")
    if not isinstance(assets, list):
        raise DashError("У релізі немає списку файлів.")
    expected_name = f"dash-client-{version}.jar"
    matching = [a for a in assets if isinstance(a, dict) and a.get("name") == expected_name]
    if len(matching) != 1 or matching[0].get("state") != "uploaded":
        raise DashError(f"У релізі {tag} немає готового {expected_name}.")
    asset = matching[0]
    checksum = asset.get("digest")
    size = asset.get("size")
    if (
        type(size) is not int
        or not 0 < size < MAX_RELEASE_BYTES
        or not isinstance(checksum, str)
        or re.fullmatch(r"sha256:[a-f0-9]{64}", checksum) is None
    ):
        raise DashError("GitHub не надав розмір і SHA-256 JAR.")
    release = Release(version, size, checksum.split(":", 1)[1])
    if asset.get("browser_download_url") != release.url:
        raise DashError("Адреса JAR не відповідає цьому публічному релізу.")
    return release


def read_release_page():
    with request_url(LATEST_PAGE, "text/html", 8) as response:
        page = urlsplit(response.geturl())
        prefix = f"/{REPOSITORY}/releases/tag/"
        if page.hostname != "github.com" or not page.path.startswith(prefix):
            raise DashError("Сторінка GitHub не вказує на стабільний реліз.")
        version = tag_version(page.path[len(prefix) :])
        body = metadata_body(response).decode("utf-8")

    expected_name = f"dash-client-{version}.jar"
    matches = {
        (int(size), sha256)
        for file_name, size, sha256 in RELEASE_MARKER.findall(body)
        if file_name == expected_name and 0 < int(size) < MAX_RELEASE_BYTES
    }
    if len(matches) != 1:
        raise DashError("На сторінці релізу відсутній однозначний розмір і SHA-256 JAR.")
    size, sha256 = matches.pop()
    return Release(version, size, sha256)


def latest_release():
    try:
        return read_release_api()
    except HTTPError as error:
        if error.code not in (403, 429):
            raise
        print("GitHub API обмежив запити. Перевіряю публічну сторінку релізу.")
        return read_release_page()


def jar_version(path, verify_archive=False):
    with zipfile.ZipFile(path) as archive:
        metadata = archive.getinfo("fabric.mod.json")
        if metadata.file_size > 64 * 1024:
            raise DashError("Метадані Fabric у JAR завеликі.")
        mod = json.loads(archive.read(metadata))
        if not isinstance(mod, dict) or mod.get("id") != "dash-client":
            raise DashError("За вибраним шляхом знаходиться інший Fabric-мод.")
        dependencies = mod.get("depends")
        if not isinstance(dependencies, dict) or dependencies.get("minecraft") not in (
            MINECRAFT_VERSION,
            f"~{MINECRAFT_VERSION}",
        ):
            raise DashError("Цей JAR не призначений для Minecraft 1.21.11.")
        version = mod.get("version")
        version_key(version)
        if verify_archive and archive.testzip() is not None:
            raise DashError("Локальний JAR пошкоджений.")
    return version


def file_hash(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(BUFFER_SIZE), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def download_jar(url, destination, size, sha256, dash_version=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=destination.parent, prefix=".dash-download-", suffix=".part", delete=False
        ) as target:
            stage = Path(target.name)
            downloaded = 0
            checksum = hashlib.sha256()
            print(f"Завантажую {destination.name} ({size / BUFFER_SIZE:.1f} МБ).", flush=True)
            with request_url(url, "application/octet-stream", 30) as response:
                for chunk in iter(lambda: response.read(BUFFER_SIZE), b""):
                    downloaded += len(chunk)
                    if downloaded > size:
                        raise DashError(f"Сервер повернув завеликий файл: {destination.name}.")
                    target.write(chunk)
                    checksum.update(chunk)
            target.flush()
            os.fsync(target.fileno())
        if downloaded != size or checksum.hexdigest() != sha256:
            raise DashError(f"Розмір або SHA-256 не збігається: {destination.name}.")
        if dash_version is not None and jar_version(stage) != dash_version:
            raise DashError("Версія у завантаженому JAR не відповідає тегу релізу.")
        try:
            os.replace(stage, destination)
        except PermissionError as error:
            raise DashError("JAR зайнятий або недоступний. Закрий Minecraft і повтори.") from error
    finally:
        if stage is not None:
            stage.unlink(missing_ok=True)


JAR_ERRORS = (DashError, OSError, ValueError, KeyError, zipfile.BadZipFile)
UPDATE_ERRORS = JAR_ERRORS + (HTTPException,)


def ensure_jar(installed_jar, update_only):
    installed = None
    if installed_jar.exists():
        try:
            installed = jar_version(installed_jar)
        except JAR_ERRORS as error:
            print(f"Локальний JAR не пройшов перевірку: {error}")
    print(f"Встановлена версія: {installed or 'відсутня або пошкоджена'}.")

    try:
        release = latest_release()
    except UPDATE_ERRORS as error:
        print(f"Остання доступна версія: не вдалося перевірити ({error}).")
        if installed is None or update_only:
            raise DashError("Перевірка релізу не завершилася.") from error
        jar_version(installed_jar, verify_archive=True)
        print(f"Запускаю наявний локальний JAR {installed}.")
        return

    print(f"Остання доступна версія: {release.version}.")
    if installed is not None:
        if version_key(installed) > version_key(release.version):
            jar_version(installed_jar, verify_archive=True)
            print("Локальна версія новіша. Залишаю її.")
            return
        if (
            installed == release.version
            and installed_jar.stat().st_size == release.size
            and file_hash(installed_jar) == release.sha256
        ):
            print("Версія актуальна. JAR повторно не завантажується.")
            return

    try:
        download_jar(release.url, installed_jar, release.size, release.sha256, release.version)
        print(f"Встановлено {release.version}. SHA-256 перевірено.")
    except UPDATE_ERRORS as error:
        if installed is None or installed == release.version or update_only:
            raise
        jar_version(installed_jar, verify_archive=True)
        print(f"Оновлення не встановлено ({error}). Використовую версію {installed}.")


# Byte-identical to the Fabric/Sodium stack used by the verified RTX profile.
MAVEN_LIBRARIES = (
    ("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar", 126151,
     "ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb"),
    ("org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar", 35140,
     "dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc"),
    ("org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar", 74840,
     "6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895"),
    ("org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar", 51958,
     "3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b"),
    ("org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar", 95628,
     "1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c"),
    ("net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar",
     1539080, "1f0ae44db7295f8626f33b1dc0ad7f043d8954a8d6847247875fcc5dfcecc934"),
    ("net/fabricmc/intermediary/1.21.11/intermediary-1.21.11-v2.jar", 707972,
     "f45b9530d0becae596fd48743e3ee1733cc2cdc69128cdb7d8d3571de163ab87"),
    ("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar", 1984980,
     "93044e4dd46de5d8136701292f05e868da096d2c9fddb4793e4fdbcc63efc695"),
)
FABRIC_API_FILE = "fabric-api-0.141.6+1.21.11.jar"
SODIUM_FILE = "sodium-fabric-0.8.14+mc1.21.11.jar"


def cached_dependency(destination, url, size, digest):
    if (
        not destination.is_file()
        or destination.stat().st_size != size
        or file_hash(destination) != digest
    ):
        download_jar(url, destination, size, digest)
    return destination.resolve()


def ensure_runtime(data_dir):
    runtime = data_dir / "runtime"
    libraries = [
        cached_dependency(
            runtime / "libraries" / filename, f"{MAVEN}/{quote(filename)}", size, digest
        )
        for filename, size, digest in MAVEN_LIBRARIES
    ]
    api_artifact = f"net/fabricmc/fabric-api/fabric-api/0.141.6+1.21.11/{FABRIC_API_FILE}"
    fabric_api = cached_dependency(
        runtime / "mods" / FABRIC_API_FILE,
        f"{MAVEN}/{quote(api_artifact)}",
        2426039,
        "bdff7fd7e220085cfad2ff9b1f40dde6534ae0b96cf378f97a374bc54cb9ed0f",
    )
    sodium = cached_dependency(
        runtime / "mods" / SODIUM_FILE,
        "https://cdn.modrinth.com/data/AANobbMI/versions/rkdTcxoT/sodium-fabric-0.8.14%2Bmc1.21.11.jar",
        1909150,
        "fd2619eff5da6b9ba6304b8b72ac3fe132c30e0bd5d675e4581eda417a272f5d",
    )
    print(f"Бібліотеки Fabric {FABRIC_VERSION}, Fabric API та Sodium готові.")
    return libraries, [fabric_api, sodium]


@contextmanager
def profile_lock(run_dir):
    import msvcrt

    run_dir.mkdir(parents=True, exist_ok=True)
    with (run_dir / "dash-client-launch.lock").open("a+b") as lock:
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise DashError("Цей профіль Dash Client вже оновлюється або працює.") from error
        yield


NICKNAME_PATTERN = re.compile(r"[A-Za-z0-9_]{3,16}")


def find_java():
    candidates = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home.strip().strip('"')))
    on_path = shutil.which("java.exe")
    if on_path:
        candidates.append(Path(on_path).resolve().parent.parent)
    vendors = ("Eclipse Adoptium", "Java", "Microsoft", "Amazon Corretto", "BellSoft",
               "Zulu", "Azul Systems", "Semeru", "SapMachine")
    roots = [Path(os.environ.get("ProgramFiles", r"C:\Program Files"))]
    local_apps = os.environ.get("LOCALAPPDATA")
    if local_apps:
        roots.append(Path(local_apps) / "Programs")
    candidates.extend((Path.home() / ".jdks").glob("*25*"))
    for root in roots:
        for directory in (root, *(root / vendor for vendor in vendors)):
            candidates.extend(directory.glob("*25*"))

    for home in dict.fromkeys(candidates):
        java = home / "bin" / "java.exe"
        if not java.is_file():
            continue
        try:
            release = (home / "release").read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            continue
        if (re.search(r'^JAVA_VERSION="25(?:[.+-][^"]*)?"$', release, re.MULTILINE)
                and re.search(r'^OS_ARCH="(?:x86_64|amd64)"$', release, re.MULTILINE)):
            return java.resolve()
    raise DashError("Потрібна Java 25 x64. Задай JAVA_HOME до папки встановленої Java 25.")


def find_minecraft():
    configured = os.environ.get("DASH_MINECRAFT_HOME")
    if configured:
        candidates = [Path(configured)]
    else:
        candidates = []
        roaming = os.environ.get("APPDATA")
        if roaming:
            candidates.append(Path(roaming) / ".minecraft")
        try:
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders",
            ) as key:
                desktop, _ = winreg.QueryValueEx(key, "Desktop")
                if isinstance(desktop, str):
                    candidates.append(Path(os.path.expandvars(desktop)) / ".minecraft")
        except OSError:
            pass
        candidates.append(Path.home() / "Desktop" / ".minecraft")

    for home in dict.fromkeys(candidates):
        version = home / "versions" / MINECRAFT_VERSION
        if all((version / f"{MINECRAFT_VERSION}.{extension}").is_file() for extension in ("json", "jar")):
            return home.resolve()
    raise DashError(
        f"Не знайдено встановлений Minecraft {MINECRAFT_VERSION}. "
        "Запусти цю версію в Minecraft Launcher або задай DASH_MINECRAFT_HOME."
    )


def is_windows_library(library):
    if library.get("name", "").endswith((":natives-windows-x86", ":natives-windows-arm64")):
        return False
    rules = library.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        operating_system = rule.get("os", {})
        if rule.get("features") or operating_system.get("name", "windows") != "windows":
            continue
        if operating_system.get("arch") and not re.search(operating_system["arch"], "amd64"):
            continue
        if operating_system.get("version") and not re.search(
            operating_system["version"], platform.version()
        ):
            continue
        allowed = rule.get("action") == "allow"
    return allowed


def minecraft_layout(home):
    version_dir = home / "versions" / MINECRAFT_VERSION
    profile = version_dir / f"{MINECRAFT_VERSION}.json"
    metadata = json.loads(profile.read_text(encoding="utf-8-sig"))
    if (not isinstance(metadata, dict)
            or metadata.get("id") != MINECRAFT_VERSION
            or metadata.get("mainClass") != "net.minecraft.client.main.Main"
            or not isinstance(metadata.get("libraries"), list)):
        raise DashError(f"Непридатний профіль Minecraft {MINECRAFT_VERSION} {profile}")

    assets = home / "assets"
    asset_index = str(metadata.get("assetIndex", {}).get("id", ""))
    if not re.fullmatch(r"[0-9]+", asset_index):
        raise DashError(f"У профілі Minecraft відсутній індекс ресурсів {profile}")
    if not (assets / "indexes" / f"{asset_index}.json").is_file() or not (assets / "objects").is_dir():
        raise DashError(f"Відсутні ресурси Minecraft {MINECRAFT_VERSION} у папці {assets}")

    classpath = []
    for library in metadata["libraries"]:
        relative = library.get("downloads", {}).get("artifact", {}).get("path")
        if not relative or not is_windows_library(library):
            continue
        location = Path(relative)
        if location.drive or location.root or ".." in location.parts:
            raise DashError(f"Неприпустимий шлях бібліотеки в профілі Minecraft {relative}")
        file = home / "libraries" / location
        if not file.is_file():
            raise DashError(f"Відсутня встановлена бібліотека Minecraft {file}")
        classpath.append(file)
    if not classpath:
        raise DashError(f"У профілі Minecraft немає бібліотек {profile}")
    classpath.append(version_dir / f"{MINECRAFT_VERSION}.jar")

    log_config = metadata.get("logging", {}).get("client", {}).get("file", {}).get("id")
    logging_file = None
    if log_config and re.fullmatch(r"[A-Za-z0-9_.-]+\.xml", log_config):
        path = assets / "log_configs" / log_config
        if path.is_file():
            logging_file = path
    return assets, asset_index, classpath, logging_file


def _read_mod_id(path):
    with zipfile.ZipFile(path) as archive:
        try:
            metadata = archive.getinfo("fabric.mod.json")
        except KeyError:
            return None
        if metadata.file_size > 256 * 1024:
            raise DashError(f"Завеликий маніфест мода {path}")
        manifest = json.loads(archive.read(metadata))
        if not isinstance(manifest, dict):
            raise DashError(f"Непридатний маніфест Fabric у файлі {path}")
        return manifest.get("id")


def _extra_mods(game_dir, mod_paths):
    configured = {_read_mod_id(path): path.resolve() for path in mod_paths}
    if None in configured or len(configured) != len(mod_paths):
        raise DashError("Не вдалося визначити моди компактного комплекту.")
    existing = set()
    for file in (game_dir / "mods").glob("*.jar"):
        try:
            mod_id = _read_mod_id(file)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            raise DashError(f"Не вдалося прочитати мод {file}") from error
        if mod_id not in configured:
            continue
        if mod_id in existing or file.resolve() != configured[mod_id]:
            raise DashError(f"Інша копія мода {mod_id} вже є у профілі {file}")
        existing.add(mod_id)
    return [path for mod_id, path in configured.items() if mod_id not in existing]


def _prefer_high_performance(java):
    try:
        with winreg.CreateKeyEx(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\DirectX\UserGpuPreferences",
            access=winreg.KEY_QUERY_VALUE | winreg.KEY_SET_VALUE,
        ) as key:
            try:
                current, _ = winreg.QueryValueEx(key, str(java))
            except FileNotFoundError:
                current = ""
            settings = [setting for setting in str(current).split(";") if setting]
            if "GpuPreference=2" not in settings:
                settings = [setting for setting in settings if not setting.startswith("GpuPreference=")]
                value = ";".join(["GpuPreference=2", *settings]) + ";"
                winreg.SetValueEx(key, str(java), 0, winreg.REG_SZ, value)
    except OSError as error:
        print(f"Windows не застосувала пріоритет відеокарти для Java. {error}", file=sys.stderr)
        return
    print(f"Режим графіки Windows для Java — висока продуктивність ({java})", flush=True)


def _java_path_list(paths):
    names = [str(path) for path in paths]
    if any(";" in name for name in names):
        raise DashError("Шляхи JAR не можуть містити крапку з комою.")
    return ";".join(names)


def _quote_java_arg(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def _saved_nickname(path):
    try:
        value = path.read_text(encoding="utf-8-sig").strip()
    except FileNotFoundError:
        return None
    return value if NICKNAME_PATTERN.fullmatch(value) else None


def play(java, data_dir, game_dir, minecraft, fabric_libraries, mod_paths):
    assets, asset_index, minecraft_libraries, logging_file = minecraft
    native_folder = game_dir / "natives"
    native_folder.mkdir(parents=True, exist_ok=True)
    options = game_dir / "options.txt"
    try:
        with options.open("x", encoding="utf-8", newline="\n") as file:
            file.write("maxFps:260\nenableVsync:false\n")
    except FileExistsError:
        pass

    mods = _extra_mods(game_dir, mod_paths)
    jvm = [
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx4G",
        f"-Djava.library.path={native_folder}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={native_folder}",
        f"-Djna.tmpdir={native_folder}",
        f"-Dio.netty.native.workdir={native_folder}",
        "-Dminecraft.launcher.brand=Dash",
        "-Dminecraft.launcher.version=1.0",
        "-Dfabric.debug.disableModIds=vulkanmod",
        "-Ddash.rtxFpsCap=840",
        "-Ddash.rtxBorderless=true",
    ]
    if mods:
        jvm.append(f"-Dfabric.addMods={_java_path_list(mods)}")
    if logging_file:
        jvm.append(f"-Dlog4j.configurationFile={logging_file}")

    nickname_file = data_dir / "run" / "dash-nickname.txt"
    override = os.environ.get("DASH_NICKNAME")
    if override and not NICKNAME_PATTERN.fullmatch(override):
        raise DashError("DASH_NICKNAME має містити від 3 до 16 латинських літер, цифр або _.")
    nickname = override or _saved_nickname(nickname_file)
    if not override:
        jvm.append(f"-Ddash.launcherNicknameFile={nickname_file}")

    classpath = _java_path_list([*fabric_libraries, *minecraft_libraries])
    environment = os.environ.copy()
    for option in ("VK_LOADER_DRIVERS_SELECT", "VK_LOADER_VENDOR_ID_FILTER", "VK_LOADER_DEVICE_ID_FILTER"):
        environment.pop(option, None)
    _prefer_high_performance(java)
    print(f"Запуск Minecraft {MINECRAFT_VERSION} через Java 25, OpenGL, ліміт 840 FPS.", flush=True)
    skip_intro = environment.get("DASH_SKIP_STARTUP") == "1"
    argument_file = game_dir / "dash-launch.args"

    while True:
        properties = []
        if skip_intro:
            properties.append("-Ddash.skipStartupIntro=true")
        if not nickname:
            properties.append("-Ddash.launcherNicknameSetup=true")
        launch = [
            *jvm, *properties, "-cp", classpath,
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--username", nickname or "Player", "--version", MINECRAFT_VERSION,
            "--gameDir", game_dir, "--assetsDir", assets, "--assetIndex", asset_index,
            "--accessToken", "0", "--versionType", "release", "--disableMultiplayer",
        ]
        argument_file.write_text(
            "\n".join(map(_quote_java_arg, launch)) + "\n", encoding="utf-8", newline="\n"
        )
        with subprocess.Popen(
            [str(java), f"@{argument_file}"],
            cwd=game_dir,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW,
        ) as process:
            for line in process.stdout:
                print(line.rstrip("\r\n"), flush=True)
            exit_code = process.wait()
        if exit_code or override:
            return exit_code
        saved = _saved_nickname(nickname_file)
        if not saved or saved == nickname:
            return exit_code
        nickname = saved
        skip_intro = True
        print("Нікнейм збережено. Перезапускаю Dash Client.", flush=True)


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure") and not stream.isatty():
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    options = argparse.ArgumentParser(description=__doc__)
    options.add_argument("--data-dir", type=Path, help="Окрема папка даних Dash Client.")
    options.add_argument("--update-only", action="store_true", help="Підготувати файли без гри.")
    arguments = options.parse_args()
    try:
        if os.name != "nt":
            raise DashError("Цей профіль призначено для Windows.")
        # Store Python redirects new AppData files. Use a location the native JVM can see.
        data_dir = (arguments.data_dir or Path.home() / "DashClientDirect" / MINECRAFT_VERSION)
        data_dir = data_dir.expanduser()
        data_dir.mkdir(parents=True, exist_ok=True)
        data_dir = data_dir.resolve(strict=True)
        java = find_java()
        minecraft = minecraft_layout(find_minecraft())
        print(f"Папка даних: {data_dir}")
        installed_jar = data_dir / INSTALL_JAR_NAME
        with profile_lock(data_dir / "run"):
            ensure_jar(installed_jar, arguments.update_only)
            libraries, mods = ensure_runtime(data_dir)
            if arguments.update_only:
                return 0
            game_dir = data_dir / "run" / "rtx-profile"
            result = play(java, data_dir, game_dir, minecraft, libraries, [installed_jar, *mods])
        print(f"Minecraft завершився з кодом {result}.")
        return result
    except UPDATE_ERRORS as error:
        print(f"Помилка Dash Client: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
