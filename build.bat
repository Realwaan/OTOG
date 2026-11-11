@echo off
echo Building OTOG E-Wallet...
if not exist "out\production\OTOG" mkdir "out\production\OTOG"
dir /s /b src\*.java test\*.java > sources.txt
javac -cp "lib/*;out/production/OTOG" -d out/production/OTOG @sources.txt
del sources.txt
if %ERRORLEVEL% EQU 0 (
    echo Build successful.
) else (
    echo Build failed with error %ERRORLEVEL%.
)
