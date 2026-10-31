@echo off
setlocal
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
if not exist build mkdir build
dir /s /b src\*.java > build\sources.txt
javac -encoding UTF-8 -d out @build\sources.txt
if errorlevel 1 exit /b 1
(
echo Manifest-Version: 1.0
echo Main-Class: app.vault.Main
echo Add-Exports: java.desktop/com.apple.eawt.event
) > build\MANIFEST.MF
jar --create --file VaultBox.jar --manifest build\MANIFEST.MF -C out .
if errorlevel 1 exit /b 1
echo 已生成 %~dp0VaultBox.jar
