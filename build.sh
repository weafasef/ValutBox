#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out build
find src -name '*.java' | sort > build/sources.txt
javac -encoding UTF-8 -d out @build/sources.txt

cat > build/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Main-Class: app.vault.Main
Add-Exports: java.desktop/com.apple.eawt.event
EOF

jar --create --file VaultBox.jar --manifest build/MANIFEST.MF -C out .
echo "已生成 $(pwd)/VaultBox.jar"
