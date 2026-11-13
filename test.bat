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

echo Running OTOG test suite...
java --enable-native-access=ALL-UNNAMED -jar "lib/junit-platform-console-standalone-1.10.2.jar" execute -cp "out/production/OTOG;lib/sqlite-jdbc-3.47.0.0.jar;lib/forms_rt.jar" --scan-classpath
set TEST_EXIT=%ERRORLEVEL%

popd
exit /b %TEST_EXIT%
