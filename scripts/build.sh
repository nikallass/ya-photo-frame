#!/usr/bin/env bash
# Сборка APK на удалённой машине с Android SDK.
#
# Нужна, когда собирать локально неудобно; на машине с установленным SDK
# достаточно обычного `./gradlew assembleDebug`. Адреса берутся из
# scripts/local.env — он не попадает в git.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$PROJECT_DIR/scripts/local.env"
[ -f "$ENV_FILE" ] || { echo "нет $ENV_FILE — см. scripts/local.env.example"; exit 1; }
# shellcheck source=/dev/null
. "$ENV_FILE"

TASK="${1:-assembleDebug}"
KEY="${BUILD_KEY/#\~/$HOME}"
SSH_OPTS=(-i "$KEY" -o StrictHostKeyChecking=accept-new)

echo "→ синхронизация исходников"
rsync -az --delete \
  --exclude '.git' --exclude 'build/' --exclude '.gradle/' --exclude 'local.properties' \
  -e "ssh ${SSH_OPTS[*]}" \
  "$PROJECT_DIR/" "$BUILD_SERVER:$BUILD_DIR/"

echo "→ сборка: $TASK"
ssh "${SSH_OPTS[@]}" "$BUILD_SERVER" "cd $BUILD_DIR && ./gradlew --no-daemon $TASK"

echo "→ забираю APK"
mkdir -p "$PROJECT_DIR/build/apk"
rsync -az -e "ssh ${SSH_OPTS[*]}" \
  "$BUILD_SERVER:$BUILD_DIR/app/build/outputs/apk/" "$PROJECT_DIR/build/apk/"

find "$PROJECT_DIR/build/apk" -name '*.apk' -printf '%p  %s байт\n'
