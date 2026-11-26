"""Install the public Dash release JAR and start the existing RTX profile."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPOSITORY = "DashCEO/dash-client-private"
REPOSITORY_URL = f"https://github.com/{REPOSITORY}"
LATEST_API = f"https://api.github.com/repos/{REPOSITORY}/releases/latest"
LATEST_PAGE = f"{REPOSITORY_URL}/releases/latest"
FOLDER = Path(__file__).resolve().parent
# This is the filename the unchanged BAT expects. The actual version is in the JAR.
INSTALLED_JAR = FOLDER / "dash-client-1.0.0.jar"
LAUNCHER = "DashClient-RTX.bat"
GITHUB_HOSTS = {
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
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


class GitHubRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        destination = urlsplit(new_url)
        if destination.scheme != "https" or destination.netloc not in GITHUB_HOSTS:
            raise DashError("GitHub перенаправив запит на непідтримувану адресу.")
        return super().redirect_request(request, fp, code, message, headers, new_url)


OPEN_URL = build_opener(GitHubRedirect()).open


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


def github_request(url, accept, timeout):
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
    with github_request(LATEST_API, "application/vnd.github+json", 8) as response:
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
    with github_request(LATEST_PAGE, "text/html", 8) as response:
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
            "1.21.11",
            "~1.21.11",
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


def install(release):
    stage = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=FOLDER, prefix=".dash-download-", suffix=".part", delete=False
        ) as target:
            stage = Path(target.name)
            downloaded = 0
            checksum = hashlib.sha256()
            print(f"Завантажую {release.asset_name} ({release.size / BUFFER_SIZE:.1f} МБ).")
            with github_request(release.url, "application/octet-stream", 30) as response:
                for chunk in iter(lambda: response.read(BUFFER_SIZE), b""):
                    downloaded += len(chunk)
                    if downloaded > release.size:
                        raise DashError("Сервер повернув більше байтів, ніж зазначено в релізі.")
                    target.write(chunk)
                    checksum.update(chunk)
            target.flush()
            os.fsync(target.fileno())
        if downloaded != release.size or checksum.hexdigest() != release.sha256:
            raise DashError("Розмір або SHA-256 завантаженого JAR не збігається.")
        if jar_version(stage) != release.version:
            raise DashError("Версія у завантаженому JAR не відповідає тегу релізу.")
        try:
            os.replace(stage, INSTALLED_JAR)
        except PermissionError as error:
            raise DashError("JAR зайнятий або недоступний. Закрий Minecraft і повтори.") from error
        print(f"Встановлено {release.version}. SHA-256 перевірено.")
    finally:
        if stage is not None:
            stage.unlink(missing_ok=True)


JAR_ERRORS = (DashError, OSError, ValueError, KeyError, zipfile.BadZipFile)
UPDATE_ERRORS = JAR_ERRORS + (HTTPException,)


def ensure_jar(update_only):
    installed = None
    if INSTALLED_JAR.exists():
        try:
            installed = jar_version(INSTALLED_JAR)
        except JAR_ERRORS as error:
            print(f"Локальний JAR не пройшов перевірку: {error}")
    print(f"Встановлена версія: {installed or 'відсутня або пошкоджена'}.")

    try:
        release = latest_release()
    except UPDATE_ERRORS as error:
        print(f"Остання доступна версія: не вдалося перевірити ({error}).")
        if installed is None or update_only:
            raise DashError("Перевірка релізу не завершилася.") from error
        jar_version(INSTALLED_JAR, verify_archive=True)
        print(f"Запускаю наявний локальний JAR {installed}.")
        return

    print(f"Остання доступна версія: {release.version}.")
    if installed is not None:
        if version_key(installed) > version_key(release.version):
            jar_version(INSTALLED_JAR, verify_archive=True)
            print("Локальна версія новіша. Залишаю її.")
            return
        if (
            installed == release.version
            and INSTALLED_JAR.stat().st_size == release.size
            and file_hash(INSTALLED_JAR) == release.sha256
        ):
            print("Версія актуальна. JAR повторно не завантажується.")
            return

    try:
        install(release)
    except UPDATE_ERRORS as error:
        if installed is None or installed == release.version or update_only:
            raise
        jar_version(INSTALLED_JAR, verify_archive=True)
        print(f"Оновлення не встановлено ({error}). Використовую версію {installed}.")


def check_local_launcher():
    required = (LAUNCHER, "DashClient.bat", "RunDashClient.ps1", "RunDashJar.ps1")
    missing = [name for name in required if not (FOLDER / name).is_file()]
    missing.extend(
        name for name in ("runtime/libraries", "runtime/mods") if not (FOLDER / name).is_dir()
    )
    if missing:
        raise DashError("Поклади файл у папку локального комплекту. Бракує: " + ", ".join(missing))


def play():
    environment = os.environ.copy()
    environment.pop("DASH_USE_GRADLE", None)
    environment["DASH_CLIENT_JAR"] = str(INSTALLED_JAR)
    cmd = Path(environment.get("SystemRoot", r"C:\Windows")) / "System32" / "cmd.exe"
    print("Запускаю DashClient-RTX.bat (RTX, ліміт 840 FPS).", flush=True)
    with subprocess.Popen(
        [str(cmd), "/d", "/c", LAUNCHER],
        cwd=FOLDER,
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
        return process.wait()


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure") and not stream.isatty():
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--update-only", action="store_true", help="Перевірити та оновити JAR без гри.")
    options = arguments.parse_args()
    try:
        if os.name != "nt":
            raise DashError("Цей RTX-комплект призначено для Windows.")
        check_local_launcher()
        ensure_jar(options.update_only)
        if options.update_only:
            return 0
        code = play()
        print(f"Запуск завершився з кодом {code}.")
        return code
    except UPDATE_ERRORS as error:
        print(f"Помилка Dash Client: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
