@echo off
setlocal EnableDelayedExpansion
pushd "%~dp0"

if exist "C:\Program Files\Java\jdk-19\bin" set "PATH=C:\Program Files\Java\jdk-19\bin;%PATH%"

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
    for /f "delims=" %%f in ('dir /s /b src\*.form 2^>nul') do (
        set "srcForm=%%f"
        set "relForm=!srcForm:%CD%\src\=!"
        for %%d in ("out\production\OTOG\!relForm!") do (
            if not exist "%%~dpd" mkdir "%%~dpd"
        )
        copy /Y "%%f" "out\production\OTOG\!relForm!" >nul
    )
    echo Build successful.
) else (
    echo Build failed with error %BUILD_EXIT%.
)

popd
exit /b %BUILD_EXIT%
