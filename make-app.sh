#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

APP="VaultBox.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp VaultBox.jar "$APP/Contents/Resources/VaultBox.jar"
cp VaultBox.icns "$APP/Contents/Resources/VaultBox.icns"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>VaultBox</string>
    <key>CFBundleDisplayName</key>
    <string>VaultBox</string>
    <key>CFBundleIdentifier</key>
    <string>app.vaultbox.desktop</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleExecutable</key>
    <string>VaultBox</string>
    <key>CFBundleIconFile</key>
    <string>VaultBox.icns</string>
    <key>LSMinimumSystemVersion</key>
    <string>11.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.utilities</string>
</dict>
</plist>
PLIST

cat > "$APP/Contents/MacOS/VaultBox" <<'LAUNCHER'
#!/bin/bash
HERE="$(cd "$(dirname "$0")" && pwd)"
RES="$(cd "$HERE/../Resources" && pwd)"
JAVA_HOME_17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
if [ -n "$JAVA_HOME_17" ] && [ -x "$JAVA_HOME_17/bin/java" ]; then
    JAVA="$JAVA_HOME_17/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
else
    osascript -e 'display dialog "需要安装 JDK 17 或更高版本才能运行 VaultBox。" buttons {"好"} with icon caution'
    exit 1
fi
exec "$JAVA" -Xdock:name=VaultBox -Xdock:icon="$RES/VaultBox.icns" -jar "$RES/VaultBox.jar" "$@"
LAUNCHER

chmod +x "$APP/Contents/MacOS/VaultBox"
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true
echo "已生成 $(pwd)/$APP"
