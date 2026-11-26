function Find-DashMinecraftHome([string]$version) {
    $candidates = @($env:DASH_MINECRAFT_HOME)
    if (!$env:DASH_MINECRAFT_HOME) {
        if ($env:APPDATA) { $candidates += Join-Path $env:APPDATA '.minecraft' }
        $desktop = [Environment]::GetFolderPath('DesktopDirectory')
        if ($desktop) { $candidates += Join-Path $desktop '.minecraft' }
        if ($env:USERPROFILE) { $candidates += Join-Path $env:USERPROFILE 'Desktop/.minecraft' }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and
                (Test-Path -LiteralPath (Join-Path $candidate "versions/$version/$version.json") -PathType Leaf -ErrorAction SilentlyContinue) -and
                (Test-Path -LiteralPath (Join-Path $candidate "versions/$version/$version.jar") -PathType Leaf -ErrorAction SilentlyContinue)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Minecraft $version is missing locally. Set DASH_MINECRAFT_HOME to an existing Minecraft folder with versions/$version, libraries and assets."
}

function Test-DashWindowsLibrary($library) {
    if ($library.name -match ':natives-windows-(?:x86|arm64)$') { return $false }
    if (!$library.rules) { return $true }

    $allowed = $false
    foreach ($rule in $library.rules) {
        if ($rule.features -or ($rule.os.name -and $rule.os.name -ne 'windows') -or
                ($rule.os.arch -and 'amd64' -notmatch $rule.os.arch) -or
                ($rule.os.version -and [Environment]::OSVersion.Version.ToString() -notmatch $rule.os.version)) {
            continue
        }
        $allowed = $rule.action -eq 'allow'
    }
    return $allowed
}

function Read-DashJarModId([string]$path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $metadata = $archive.GetEntry('fabric.mod.json')
        if (!$metadata) { return }
        $reader = [IO.StreamReader]::new($metadata.Open())
        try {
            return ($reader.ReadToEnd() | ConvertFrom-Json).id
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
}

function ConvertTo-DashJavaArgument([string]$value) {
    return '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-DashJar([string]$jdkHome, [string]$dashJar, [string]$gameDir, [bool]$rtx) {
    $version = '1.21.11'
    $loaderVersion = '0.19.5'
    $minecraftHome = Find-DashMinecraftHome $version
    $clientJar = Join-Path $minecraftHome "versions/$version/$version.jar"
    $versionFile = Join-Path $minecraftHome "versions/$version/$version.json"
    $metadata = Get-Content -LiteralPath $versionFile -Raw | ConvertFrom-Json
    if ($metadata.id -ne $version -or $metadata.mainClass -ne 'net.minecraft.client.main.Main' -or
            !$metadata.libraries -or !$metadata.assetIndex.id) {
        throw "The local version metadata is not a vanilla Minecraft $version profile: $versionFile"
    }

    $assets = Join-Path $minecraftHome 'assets'
    $assetIndex = [string]$metadata.assetIndex.id
    if ($assetIndex -notmatch '^\d+$' -or
            !(Test-Path -LiteralPath (Join-Path $assets "indexes/$assetIndex.json") -PathType Leaf) -or
            !(Test-Path -LiteralPath (Join-Path $assets 'objects') -PathType Container)) {
        throw "Minecraft $version assets are missing. The local Minecraft folder must contain assets/indexes/$assetIndex.json and assets/objects."
    }

    $mojangLibraries = Join-Path $minecraftHome 'libraries'
    $fabricLibraries = if ($env:DASH_FABRIC_LIBRARIES) { $env:DASH_FABRIC_LIBRARIES } else {
        Join-Path $PSScriptRoot 'runtime/libraries'
    }
    $fabricCoordinates = @(
        'org.ow2.asm:asm:9.10.1'
        'org.ow2.asm:asm-analysis:9.10.1'
        'org.ow2.asm:asm-commons:9.10.1'
        'org.ow2.asm:asm-tree:9.10.1'
        'org.ow2.asm:asm-util:9.10.1'
        'net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7'
        "net.fabricmc:intermediary:$version"
        "net.fabricmc:fabric-loader:$loaderVersion"
    )
    $classpath = @(
        foreach ($coordinate in $fabricCoordinates) {
            $group, $name, $revision = $coordinate -split ':'
            $directory = "$($group.Replace('.', '/'))/$name/$revision"
            $alternatives = @("$name-$revision.jar")
            if ($name -eq 'intermediary') { $alternatives += "$name-$revision-v2.jar" }
            $match = $null
            foreach ($root in @($fabricLibraries, $mojangLibraries)) {
                foreach ($filename in $alternatives) {
                    $file = Join-Path $root "$directory/$filename"
                    if (Test-Path -LiteralPath $file -PathType Leaf) {
                        $match = (Resolve-Path -LiteralPath $file).Path
                        break
                    }
                }
                if ($match) { break }
            }
            if (!$match) { throw "Missing Fabric runtime library $coordinate. Check $fabricLibraries." }
            $match
        }
        foreach ($library in $metadata.libraries) {
            if (!(Test-DashWindowsLibrary $library) -or !$library.downloads.artifact.path) { continue }
            $file = Join-Path $mojangLibraries $library.downloads.artifact.path
            if (!(Test-Path -LiteralPath $file -PathType Leaf)) {
                throw "Missing Minecraft runtime library: $file"
            }
            $file
        }
        $clientJar
    )

    if (!(Test-Path -LiteralPath $dashJar -PathType Leaf)) { throw "Dash JAR is missing: $dashJar" }
    $dashJar = (Resolve-Path -LiteralPath $dashJar).Path
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if ((Read-DashJarModId $dashJar) -ne 'dash-client') { throw "Expected a Dash Client release JAR: $dashJar" }
    $gameMods = Join-Path $gameDir 'mods'
    $installedMods = @{}
    foreach ($mod in Get-ChildItem -LiteralPath $gameMods -Filter '*.jar' -File -ErrorAction SilentlyContinue) {
        $id = Read-DashJarModId $mod.FullName
        if ($id) { $installedMods[$id] = $mod.FullName }
    }
    if ($installedMods['dash-client'] -and $installedMods['dash-client'] -ne $dashJar) {
        throw "Another Dash Client JAR is installed in $gameMods. Keep one Dash Client JAR for this profile."
    }

    $modsToLoad = @()
    if (!$installedMods['dash-client']) { $modsToLoad += $dashJar }
    foreach ($mod in @(
        @{ Id = 'fabric-api'; Override = $env:DASH_FABRIC_API_JAR; File = 'fabric-api-0.141.6+1.21.11.jar' },
        @{ Id = 'sodium'; Override = $env:DASH_SODIUM_JAR; File = 'sodium-fabric-0.8.14+mc1.21.11.jar' }
    )) {
        if ($installedMods[$mod.Id]) { continue }
        $path = if ($mod.Override) { $mod.Override } else { Join-Path $PSScriptRoot "runtime/mods/$($mod.File)" }
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) {
            if ($mod.Override -or $mod.Id -eq 'fabric-api') { throw "Missing $($mod.Id) JAR: $path" }
            continue
        }
        $path = (Resolve-Path -LiteralPath $path).Path
        if ((Read-DashJarModId $path) -ne $mod.Id) { throw "Expected $($mod.Id) in $path" }
        $modsToLoad += $path
    }

    $java = Join-Path $jdkHome 'bin/java.exe'
    if ($rtx) {
        Clear-DashVulkanSelection
        Set-DashHighPerformanceGpu $jdkHome
        Write-Host 'Starting Dash JAR with Java 25, borderless OpenGL and an 840 FPS cap.'
    } else {
        Write-Host 'Starting Dash JAR with Java 25 and OpenGL.'
    }

    $nativePath = Join-Path $gameDir 'natives'
    [void][IO.Directory]::CreateDirectory($nativePath)
    $optionsFile = Join-Path $gameDir 'options.txt'
    $utf8 = [Text.UTF8Encoding]::new($false)
    if ($rtx -and !(Test-Path -LiteralPath $optionsFile -PathType Leaf)) {
        [IO.File]::WriteAllLines($optionsFile, [string[]]@('maxFps:260', 'enableVsync:false'), $utf8)
    }
    $jvm = @('--enable-native-access=ALL-UNNAMED', '-Xmx4G',
        "-Djava.library.path=$nativePath", "-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativePath",
        "-Djna.tmpdir=$nativePath", "-Dio.netty.native.workdir=$nativePath",
        '-Dminecraft.launcher.brand=Dash', '-Dminecraft.launcher.version=1.0',
        '-Dfabric.debug.disableModIds=vulkanmod')
    if ($rtx) { $jvm += @('-Ddash.rtxFpsCap=840', '-Ddash.rtxBorderless=true') }
    if ($modsToLoad.Count) { $jvm += "-Dfabric.addMods=$($modsToLoad -join ';')" }
    if ($metadata.logging.client.file.id) {
        $loggingFile = Join-Path $assets "log_configs/$($metadata.logging.client.file.id)"
        if (Test-Path -LiteralPath $loggingFile -PathType Leaf) {
            $jvm += "-Dlog4j.configurationFile=$loggingFile"
        }
    }

    $nicknameFile = if ($env:DASH_GAME_DIR) { Join-Path $gameDir 'dash-nickname.txt' } else {
        Join-Path $PSScriptRoot 'run/dash-nickname.txt'
    }
    $nickname = Read-DashNickname $nicknameFile
    if ($env:DASH_NICKNAME) {
        if ($env:DASH_NICKNAME -cnotmatch '^[A-Za-z0-9_]{3,16}$') { throw 'DASH_NICKNAME must be 3–16 letters, digits or underscores.' }
        $nickname = $env:DASH_NICKNAME
    } else {
        $jvm += "-Ddash.launcherNicknameFile=$nicknameFile"
    }
    $launchFile = Join-Path $gameDir 'dash-launch.args'
    $skipIntro = $env:DASH_SKIP_STARTUP -eq '1'
    Push-Location -LiteralPath $gameDir
    try {
        do {
            $properties = @()
            if ($skipIntro) { $properties += '-Ddash.skipStartupIntro=true' }
            if (!$nickname) { $properties += '-Ddash.launcherNicknameSetup=true' }
            $playerName = if ($nickname) { $nickname } else { 'Player' }
            $launch = $jvm + $properties + @('-cp', ($classpath -join ';'),
                'net.fabricmc.loader.impl.launch.knot.KnotClient',
                '--username', $playerName, '--version', $version,
                '--gameDir', $gameDir, '--assetsDir', $assets, '--assetIndex', $assetIndex,
                '--accessToken', '0', '--versionType', 'release', '--disableMultiplayer')
            [IO.File]::WriteAllLines($launchFile, [string[]]@($launch | ForEach-Object { ConvertTo-DashJavaArgument $_ }), $utf8)
            $started = [datetime]::UtcNow
            & $java "@$launchFile" | Out-Host
            $exitCode = $LASTEXITCODE
            if ($rtx -and $exitCode -ne 0 -and (Test-DashNvidiaOpenGlFailure $gameDir $started)) {
                Write-Host 'The NVIDIA OpenGL driver failed while presenting a frame. Check the hs_err_pid log in the game folder.'
            }
            if ($exitCode -ne 0 -or $env:DASH_NICKNAME) { break }
            $savedNickname = Read-DashNickname $nicknameFile
            if (!$savedNickname -or $savedNickname -ceq $nickname) { break }
            Write-Host 'Nickname saved. Restarting Dash Client with the selected local profile.'
            $nickname = $savedNickname
            $skipIntro = $true
        } while ($true)
    } finally {
        Pop-Location
    }
    return $exitCode
}
