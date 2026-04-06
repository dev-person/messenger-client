#!/usr/bin/env bash
# deploy.sh — запуск с машины разработчика
# Использование:
#   ./deploy.sh                — только сервер (по умолчанию)
#   ./deploy.sh onlyClient     — только клиент (сборка APK, загрузка, обновление версии в БД)
set -euo pipefail

SERVER="root@82.22.187.136"
REMOTE_SCRIPT="/opt/messenger-server/scripts/server-deploy.sh"
BRANCH="main"
SSH_OPTS="-o ServerAliveInterval=15 -o ServerAliveCountMax=40"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
REMOTE_APK="/opt/messenger-server/static/messenger.apk"
DOWNLOAD_URL="http://82.22.187.136:8080/static/messenger.apk"

MODE="${1:-server}"

if [ "$MODE" = "onlyClient" ]; then
    # ── Только клиент ─────────────────────────────────────────────────────────

    echo "==> [1/3] Сборка APK..."
    ./gradlew assembleDebug --quiet

    VERSION_CODE=$(grep 'versionCode' app/build.gradle.kts | head -1 | sed 's/[^0-9]//g')
    VERSION_NAME=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
    echo "    Версия: $VERSION_NAME (code=$VERSION_CODE)"

    echo ""
    echo "==> [2/3] Загрузка APK на сервер..."
    scp "$APK_PATH" "$SERVER:$REMOTE_APK"
    echo "    Загружено: $REMOTE_APK"

    echo ""
    echo "==> [3/3] Обновление версии в БД..."
    ssh $SSH_OPTS "$SERVER" "docker compose -f /opt/messenger-server/docker-compose.yml exec -T postgres psql -U messenger -d messenger -c \"
UPDATE app_settings SET value = '$VERSION_CODE', updated_at = NOW() WHERE key = 'app_version_code';
UPDATE app_settings SET value = '$VERSION_NAME', updated_at = NOW() WHERE key = 'app_version_name';
UPDATE app_settings SET value = '$DOWNLOAD_URL', updated_at = NOW() WHERE key = 'app_download_url';
\""

    echo ""
    echo "==> Деплой клиента завершён!"
    echo "    APK: v$VERSION_NAME (code=$VERSION_CODE)"
    echo "    URL: $DOWNLOAD_URL"

else
    # ── Только сервер ─────────────────────────────────────────────────────────

    echo "==> [1/1] Деплой сервера (ветка '$BRANCH')..."
    ssh $SSH_OPTS "$SERVER" "bash $REMOTE_SCRIPT $BRANCH"

    echo ""
    echo "==> Деплой сервера завершён!"
fi
