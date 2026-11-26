$ErrorActionPreference = 'Stop'

function Find-DashJdk25 {
    $candidates = @(
        $env:JAVA_HOME
        Get-Command java.exe -CommandType Application -All -ErrorAction SilentlyContinue | ForEach-Object {
            Split-Path -Parent (Split-Path -Parent $_.Source)
        }
        $roots = @($env:ProgramFiles)
        if ($env:LOCALAPPDATA) { $roots += Join-Path $env:LOCALAPPDATA 'Programs' }
        foreach ($root in $roots) {
            if (!$root) { continue }
            Get-ChildItem -LiteralPath $root -Directory -Filter '*25*' -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty FullName
            foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft', 'Amazon Corretto', 'BellSoft', 'Zulu', 'Azul Systems', 'Semeru', 'SapMachine')) {
                Get-ChildItem -LiteralPath (Join-Path $root $vendor) -Directory -Filter '*25*' -ErrorAction SilentlyContinue |
                    Select-Object -ExpandProperty FullName
            }
        }
        if ($env:USERPROFILE) {
            Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.jdks') -Directory -Filter '*25*' -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty FullName
        }
    )

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $homePath = $candidate.Trim().Trim('"')
        if ([string]::IsNullOrWhiteSpace($homePath)) { continue }
        if (!(Test-Path -LiteralPath (Join-Path $homePath 'bin/java.exe') -PathType Leaf) -or
                !(Test-Path -LiteralPath (Join-Path $homePath 'bin/javac.exe') -PathType Leaf)) { continue }
        if (Select-String -LiteralPath (Join-Path $homePath 'release') -Pattern '^JAVA_VERSION="25(?:[.+-][^"]*)?"$' -Quiet -ErrorAction SilentlyContinue) {
            return (Resolve-Path -LiteralPath $homePath).Path
        }
    }
}

function Find-DashLocalGradle([string]$cache) {
    $properties = Join-Path $PSScriptRoot 'gradle/wrapper/gradle-wrapper.properties'
    $distribution = Select-String -LiteralPath $properties -Pattern '^distributionUrl=.*/gradle-([0-9.]+)-(bin|all)\.zip\s*$'
    if (!$distribution) { throw 'Could not read the Gradle version from gradle-wrapper.properties.' }
    $version = $distribution.Matches[0].Groups[1].Value
    $type = $distribution.Matches[0].Groups[2].Value

    $candidates = @(
        $env:GRADLE_HOME
        $distributions = Join-Path $cache "wrapper/dists/gradle-$version-$type"
        foreach ($directory in Get-ChildItem -LiteralPath $distributions -Directory -ErrorAction SilentlyContinue) {
            Join-Path $directory.FullName "gradle-$version"
        }
    )

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $launcher = Join-Path $candidate 'bin/gradle.bat'
        $launcherLibrary = Join-Path $candidate "lib/gradle-launcher-$version.jar"
        if ((Test-Path -LiteralPath $launcher -PathType Leaf) -and
                (Test-Path -LiteralPath $launcherLibrary -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $launcher).Path
        }
    }
    throw "Gradle $version is missing locally. Set GRADLE_HOME to an existing Gradle $version installation or GRADLE_USER_HOME to a cache containing it. No download was attempted."
}

function Test-SharingViolation($errorRecord) {
    $exception = $errorRecord.Exception.GetBaseException()
    return $exception -is [IO.IOException] -and ($exception.HResult -band 0xFFFF) -in 32, 33
}

function Get-DashJavaRendererFailure([string]$report) {
    if ($report -notmatch 'net\.vulkanmod\.') { return }
    if ($report -match 'VK_ERROR_(?:DEVICE_LOST|SURFACE_LOST_KHR|OUT_OF_DATE_KHR)') {
        return 'Vulkan lost the graphics device or surface'
    }
    if ($report -match 'NullPointerException[^\r\n]*getDepthAttachment\(\)' -and
            $report -match 'net\.vulkanmod\.vulkan\.framebuffer\.RenderPass\.beginDynamicRendering') {
        return 'Vulkan framebuffer lost its depth attachment'
    }
    if ($report -match 'IndexOutOfBoundsException' -and
            $report -match 'net\.vulkanmod\.vulkan\.(?:pass\.DefaultMainPass|framebuffer\.SwapChain)\.getColorAttachment') {
        return 'Vulkan swapchain returned an invalid image index'
    }
}

function Get-DashRendererFailure([string]$gameDir, [datetime]$sinceUtc) {
    $reports = Join-Path $gameDir 'crash-reports'
    foreach ($file in Get-ChildItem -LiteralPath $reports -Filter 'crash-*-client.txt' -File -ErrorAction SilentlyContinue |
            Where-Object LastWriteTimeUtc -GE $sinceUtc | Sort-Object LastWriteTimeUtc -Descending) {
        $report = (Get-Content -LiteralPath $file.FullName -TotalCount 80 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
        $failure = Get-DashJavaRendererFailure $report
        if ($failure) { return $failure }
    }

    $log = Get-Item -LiteralPath (Join-Path $gameDir 'logs/latest.log') -ErrorAction SilentlyContinue
    if ($log -and $log.LastWriteTimeUtc -ge $sinceUtc) {
        $tail = (Get-Content -LiteralPath $log.FullName -Tail 250 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
        $failure = Get-DashJavaRendererFailure $tail
        if ($failure) { return $failure }
    }

    foreach ($file in Get-ChildItem -LiteralPath $gameDir -Filter 'hs_err_pid*.log' -File -ErrorAction SilentlyContinue |
            Where-Object LastWriteTimeUtc -GE $sinceUtc | Sort-Object LastWriteTimeUtc -Descending) {
        $report = (Get-Content -LiteralPath $file.FullName -TotalCount 130 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
        if ($report -notmatch '(?m)^#\s+EXCEPTION_ACCESS_VIOLATION') { continue }
        # Do not reopen Minecraft after a deliberate Quit followed by a cleanup crash.
        if ($report -match 'net\.vulkanmod\.vulkan\.(?:Renderer\.cleanUpResources|Vulkan\.cleanUp)') {
            return 'Vulkan renderer shutdown'
        }
        if ($report -match '(?m)^# C\s+\[(?:nvoglv64|vulkan-1)\.dll\+' -or
                ($report -match 'org\.lwjgl\.vulkan\.' -and $report -match 'net\.vulkanmod\.')) {
            return 'Vulkan/NVIDIA native graphics crash'
        }
    }
}

function Clear-DashVulkanSelection {
    Remove-Item Env:VK_LOADER_DRIVERS_SELECT,Env:VK_LOADER_VENDOR_ID_FILTER,Env:VK_LOADER_DEVICE_ID_FILTER -ErrorAction SilentlyContinue
}

function Set-DashHighPerformanceGpu([string]$jdkHome) {
    $javaPath = (Resolve-Path -LiteralPath (Join-Path $jdkHome 'bin/java.exe')).Path
    $preferences = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\Microsoft\DirectX\UserGpuPreferences')
    try {
        $current = [string]$preferences.GetValue($javaPath, '')
        if ($current -notmatch '(?:^|;)GpuPreference=2(?:;|$)') {
            $otherSettings = @($current -split ';' | Where-Object { $_ -and $_ -notmatch '^GpuPreference=' })
            $updated = (@('GpuPreference=2') + $otherSettings) -join ';'
            $preferences.SetValue($javaPath, "$updated;", [Microsoft.Win32.RegistryValueKind]::String)
        }
        Write-Host "Windows graphics preference: High performance ($javaPath)"
    } finally {
        $preferences.Dispose()
    }
}

function Test-DashProfileHasSodium([string]$gameDir) {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $gameDir 'mods') -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    if (!$jars.Count) { return $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($jar in $jars) {
        try {
            $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
            try {
                $metadata = $archive.GetEntry('fabric.mod.json')
                if ($null -eq $metadata) { continue }
                $reader = [IO.StreamReader]::new($metadata.Open())
                try {
                    if (($reader.ReadToEnd() | ConvertFrom-Json).id -eq 'sodium') { return $true }
                } finally {
                    $reader.Dispose()
                }
            } finally {
                $archive.Dispose()
            }
        } catch {
            # Fabric reports invalid or unreadable mod files when it loads the profile.
        }
    }
    return $false
}

function Get-DashSodiumJar([string]$gameDir) {
    if (Test-DashProfileHasSodium $gameDir) { return }

    if ($env:DASH_SODIUM_JAR) {
        if (!(Test-Path -LiteralPath $env:DASH_SODIUM_JAR -PathType Leaf)) {
            throw "DASH_SODIUM_JAR does not point to an existing file: $env:DASH_SODIUM_JAR"
        }
        return (Resolve-Path -LiteralPath $env:DASH_SODIUM_JAR).Path
    }

    $legacy = Join-Path $PSScriptRoot 'run/mods/sodium-fabric-0.8.14-mc1.21.11.jar'
    if (Test-Path -LiteralPath $legacy -PathType Leaf) { return $legacy }

    $jar = Join-Path $PSScriptRoot '.gradle/dash-runtime/sodium-fabric-0.8.14+mc1.21.11.jar'
    $sha512 = '04C43F9E8534B87A52C42FFD51B0E344D4EF92DC9CC52DA33D13AF6A18BF74B05380D2027F1E0DB982698D0AFF274C42741C1E21B313B0CD75ED3AF005FC98D9'
    if ((Test-Path -LiteralPath $jar -PathType Leaf) -and
            (Get-FileHash -LiteralPath $jar -Algorithm SHA512).Hash -eq $sha512) { return $jar }

    Write-Warning 'Local Sodium was not found or its cached copy is invalid. Using the standard OpenGL renderer. Set DASH_SODIUM_JAR to a compatible local Sodium JAR to enable it.'
}

function Test-DashNvidiaOpenGlFailure([string]$gameDir, [datetime]$sinceUtc) {
    foreach ($file in Get-ChildItem -LiteralPath $gameDir -Filter 'hs_err_pid*.log' -File -ErrorAction SilentlyContinue |
            Where-Object LastWriteTimeUtc -GE $sinceUtc) {
        $report = (Get-Content -LiteralPath $file.FullName -TotalCount 130 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
        if ($report -match '(?m)^#\s+EXCEPTION_ACCESS_VIOLATION' -and
                $report -match '(?m)^# C\s+\[nvapi64_impl\.dll\+' -and
                $report -match 'org\.lwjgl\.glfw\.GLFW\.glfwSwapBuffers|com\.mojang\.blaze3d\.systems\.RenderSystem\.flipFrame|com\.mojang\.blaze3d\.platform\.Window\.updateDisplay') {
            return $true
        }
    }
    return $false
}

function Read-DashNickname([string]$path) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { return }
    $nickname = [IO.File]::ReadAllText($path).Trim()
    if ($nickname -cmatch '^[A-Za-z0-9_]{3,16}$') { return $nickname }
}

$lock = $null
try {
    $dashJar = if ($env:DASH_CLIENT_JAR) { $env:DASH_CLIENT_JAR } else {
        Join-Path $PSScriptRoot 'dash-client-1.0.0.jar'
    }
    $sourceAvailable = Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build.gradle') -PathType Leaf
    $jarMode = $env:DASH_USE_GRADLE -ne '1' -and (
        $env:DASH_CLIENT_JAR -or !$sourceAvailable -or (Test-Path -LiteralPath $dashJar -PathType Leaf))
    $rtxProfile = $env:DASH_RTX_PROFILE -eq '1'
    $runDir = Join-Path $PSScriptRoot 'run'
    $profileDir = if ($jarMode -and $env:DASH_GAME_DIR) {
        [IO.Path]::GetFullPath($env:DASH_GAME_DIR)
    } elseif ($rtxProfile) {
        Join-Path $runDir 'rtx-profile'
    } else { $runDir }
    $lockDir = if ($jarMode -and $env:DASH_GAME_DIR) { $profileDir } else { $runDir }
    [void][IO.Directory]::CreateDirectory($lockDir)
    $lockPath = Join-Path $lockDir 'dash-client-launch.lock'
    try {
        $lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch {
        if (!(Test-SharingViolation $_)) { throw }
        Write-Host 'Dash Client is already running or starting. Use the open Minecraft window.'
        exit 0
    }

    $jdk25 = Find-DashJdk25
    if (!$jdk25) {
        Write-Host '[ERROR] JDK 25 not found. Install a Windows JDK 25 or point JAVA_HOME to your existing JDK 25 directory.'
        Write-Host 'CMD example: set "JAVA_HOME=D:\Java\jdk-25"'
        exit 1
    }
    $env:JAVA_HOME = $jdk25

    if ($jarMode) {
        $jarRunner = Join-Path $PSScriptRoot 'RunDashJar.ps1'
        if (!(Test-Path -LiteralPath $jarRunner -PathType Leaf)) {
            throw 'RunDashJar.ps1 is missing. Extract the complete Dash JAR release.'
        }
        . $jarRunner
        [void][IO.Directory]::CreateDirectory($profileDir)
        exit (Invoke-DashJar $jdk25 $dashJar $profileDir $rtxProfile)
    }

    foreach ($relativeFile in @('build.gradle', 'settings.gradle', 'gradle.properties',
            'gradle/wrapper/gradle-wrapper.properties', 'src/main/resources/fabric.mod.json',
            'src/client/java/com/dash/client/client/DashClientClient.java')) {
        if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot $relativeFile) -PathType Leaf)) {
            throw "Required project file is missing: $relativeFile. Extract the full Dash Client project."
        }
    }

    $cachePaths = @('run\.fabric\processedMods', 'run\rtx-profile\.fabric\processedMods')
    foreach ($relativeCache in $cachePaths) {
        $cache = Join-Path $PSScriptRoot $relativeCache
        if (!(Test-Path -LiteralPath $cache -PathType Container)) { continue }
        foreach ($jar in Get-ChildItem -LiteralPath $cache -Filter '*.jar' -File) {
            try {
                $probe = [IO.File]::Open($jar.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
                $probe.Dispose()
            } catch {
                $exception = $_.Exception.GetBaseException()
                if ($exception -is [IO.FileNotFoundException]) { continue }
                if (!(Test-SharingViolation $_)) { throw }
                Write-Host 'The Fabric mod cache is in use. Close the other Minecraft window before restarting Dash Client.'
                exit 0
            }
        }
    }

    $gradleCache = $env:GRADLE_USER_HOME
    if (!$gradleCache) { $gradleCache = Join-Path $env:USERPROFILE '.gradle' }
    $gradle = Find-DashLocalGradle $gradleCache
    $offlineArgs = @('--offline', '--no-daemon', '-g', $gradleCache,
        "-Dorg.gradle.java.home=$jdk25", '-Porg.gradle.java.installations.auto-download=false')
    if ($env:DASH_RTX_PROFILE -eq '1') {
        Set-DashHighPerformanceGpu $jdk25
        $offlineArgs += @('-Porg.gradle.java.installations.auto-detect=false',
            "-Porg.gradle.java.installations.paths=$jdk25")
    }
    Write-Host "Dash offline launch. Gradle cache: $gradleCache"
    Write-Host "JDK 25: $jdk25"

    $nicknamePath = Join-Path $PSScriptRoot 'run/dash-nickname.txt'
    $nickname = Read-DashNickname $nicknamePath
    $env:DASH_NICKNAME_FILE = $nicknamePath
    $env:DASH_SKIP_STARTUP = $null
    $gradleTask = 'runClient'
    $rtxDir = Join-Path $PSScriptRoot 'run/rtx-profile'
    $launchVulkan = $false
    $launchOpenGl = $false
    if ($env:DASH_RTX_PROFILE -eq '1' -and $env:DASH_STABLE_PROFILE -ne '1') {
        $vulkanMod = Join-Path $rtxDir 'mods/VulkanMod-0.6.8+1.21.11.jar'
        $launchVulkan = Test-Path -LiteralPath $vulkanMod -PathType Leaf
        if (!$launchVulkan) {
            Write-Host 'VulkanMod is missing. Starting the RTX profile with OpenGL.'
        }
    }
    if ($launchVulkan) {
        $env:VK_LOADER_DRIVERS_SELECT = 'nv-vk64.json'
        $env:VK_LOADER_VENDOR_ID_FILTER = '0x10de'
        $env:VK_LOADER_DEVICE_ID_FILTER = '0x2f04'
        $gradleTask = 'runRtxClient'
        Write-Host 'Starting Dash Client RTX (Vulkan)...'
    } elseif ($env:DASH_RTX_PROFILE -eq '1') {
        $launchOpenGl = $true
        Clear-DashVulkanSelection
        $env:DASH_SODIUM_JAR = Get-DashSodiumJar $rtxDir
        $gradleTask = 'runRtxSafeClient'
        Write-Host 'Starting the Dash RTX profile with borderless OpenGL (840 FPS cap)...'
    } else {
        Write-Host 'Starting Dash Client...'
    }
    Push-Location -LiteralPath $PSScriptRoot
    try {
        do {
            $needsNickname = [string]::IsNullOrEmpty($nickname)
            $env:DASH_NICKNAME_SETUP = if ($needsNickname) { '1' } else { $null }
            $userArgument = if ($needsNickname) { '--args=--disableMultiplayer' } else { "--args=--username $nickname" }
            $launchTimeUtc = [datetime]::UtcNow
            & $gradle @offlineArgs $gradleTask $userArgument
            $exitCode = $LASTEXITCODE
            if ($launchVulkan -and $exitCode -ne 0) {
                $rendererFailure = Get-DashRendererFailure $rtxDir $launchTimeUtc
                if ($rendererFailure -eq 'Vulkan renderer shutdown') {
                    Write-Host 'Vulkan failed during shutdown. DashClient-Stable.bat starts the same profile with OpenGL.'
                } elseif ($rendererFailure) {
                    Write-Host "$rendererFailure. Restarting once with OpenGL in the same game profile."
                    Clear-DashVulkanSelection
                    $env:DASH_SODIUM_JAR = Get-DashSodiumJar $rtxDir
                    $launchVulkan = $false
                    $launchOpenGl = $true
                    $gradleTask = 'runRtxSafeClient'
                    & $gradle @offlineArgs $gradleTask $userArgument
                    $exitCode = $LASTEXITCODE
                }
            } elseif ($launchOpenGl -and $exitCode -ne 0 -and
                    (Test-DashNvidiaOpenGlFailure $rtxDir $launchTimeUtc)) {
                Write-Host 'The NVIDIA OpenGL driver failed while presenting a frame. See the latest hs_err_pid log in run/rtx-profile.'
            }

            if ($exitCode -ne 0) { break }
            $savedNickname = Read-DashNickname $nicknamePath
            if ([string]::IsNullOrEmpty($savedNickname) -or $savedNickname -ceq $nickname) { break }
            Write-Host 'Nickname saved. Restarting Dash Client with the selected profile.'
            $nickname = $savedNickname
            $env:DASH_SKIP_STARTUP = '1'
        } while ($true)
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        Write-Host '[ERROR] Offline launch failed. If Gradle reports a missing dependency or asset, point GRADLE_USER_HOME to an existing cache with this Minecraft/Fabric version.'
    }
    exit $exitCode
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)"
    exit 1
} finally {
    if ($null -ne $lock) { $lock.Dispose() }
}
