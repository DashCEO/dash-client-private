@echo off
setlocal
rem The release uses its local Dash JAR. The source tree can use Gradle for development.
set "DASH_RTX_PROFILE=1"
set "DASH_STABLE_PROFILE=1"
if /i "%~1"=="--source" set "DASH_USE_GRADLE=1"
if /i "%~1"=="--vulkan" (
    set "DASH_STABLE_PROFILE="
    set "DASH_USE_GRADLE=1"
)
call "%~dp0DashClient.bat"
exit /b %ERRORLEVEL%
