#!/usr/bin/env bash
cd "$(dirname "$0")"
exec java -Dapple.awt.application.name=VaultBox -jar VaultBox.jar "$@"
