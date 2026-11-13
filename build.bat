@echo off
setlocal EnableDelayedExpansion
pushd "%~dp0"

echo Building OTOG E-Wallet...
if not exist "out\production\OTOG" mkdir "out\production\OTOG"

> sources.txt (
    for /f "delims=" %%f in ('dir /s /b src\*.java test\*.java') do (
        set "line=%%f"
        set "line=!line:\=/!"
        echo "!line!"
    )
)

javac -encoding UTF-8 -cp "lib/*;out/production/OTOG" -d "out/production/OTOG" @sources.txt
set BUILD_EXIT=%ERRORLEVEL%
if exist sources.txt del sources.txt

if %BUILD_EXIT% EQU 0 (
    if exist "src\UserLogin.form" copy /Y "src\UserLogin.form" "out\production\OTOG\UserLogin.form" >nul
    echo Build successful.
) else (
    echo Build failed with error %BUILD_EXIT%.
)

popd
exit /b %BUILD_EXIT%
