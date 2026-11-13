@echo off
setlocal
pushd "%~dp0"

if not exist "out\production\OTOG\UserLogin.class" (
    call build.bat
    if %ERRORLEVEL% NEQ 0 (
        popd
        exit /b %ERRORLEVEL%
    )
)

echo Launching OTOG E-Wallet User Login...
java --enable-native-access=ALL-UNNAMED -cp "out/production/OTOG;lib/*" UserLogin %*
set RUN_EXIT=%ERRORLEVEL%

popd
exit /b %RUN_EXIT%
