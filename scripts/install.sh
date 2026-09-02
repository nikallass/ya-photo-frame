#!/usr/bin/env bash
# Установка собранного APK на телевизор по сети. Адрес — в scripts/local.env.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$PROJECT_DIR/scripts/local.env"
[ -f "$ENV_FILE" ] || { echo "нет $ENV_FILE — см. scripts/local.env.example"; exit 1; }
# shellcheck source=/dev/null
. "$ENV_FILE"

APK="${1:-$(find "$PROJECT_DIR/build/apk" -name 'app-debug.apk' | head -1)}"
[ -f "$APK" ] || { echo "APK не найден — сначала scripts/build.sh"; exit 1; }

adb connect "$TV" >/dev/null
adb -s "$TV" install -r "$APK"
echo "установлено: $APK"
