#!/usr/bin/env bash
# deploy.sh — запуск с машины разработчика
# Использование:
#   ./deploy.sh                — только сервер (по умолчанию)
#   ./deploy.sh onlyClient     — только клиент (сборка APK, регистрация версии, загрузка)
set -euo pipefail

SERVER="root@80.87.103.108"
REMOTE_SCRIPT="/opt/messenger-server/scripts/server-deploy.sh"
BRANCH="main"
SSH_OPTS="-o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=40"
APK_PATH="app/build/outputs/apk/release/app-release.apk"
REMOTE_APK="/opt/messenger-server/static/messenger.apk"
DOWNLOAD_URL="https://grizzly-messenger.ru/static/messenger.apk"
CHANGELOG_FILE="CHANGELOG.md"
ADMIN_API="https://grizzly-messenger.ru/admin/versions"
ADMIN_SECRET="${ADMIN_SECRET:-4e590ac97dadaf4e56ef099dfb8b68e6}"

MODE="${1:-server}"

# ── Парсинг последней (верхней) записи из CHANGELOG.md ────────────────────────
# Формат записи:
#   ## [versionName] (versionCode) — дата
#
#   - пункт 1
#   - пункт 2
#
# Возвращает changelog как многострочный текст (без заголовка).
# Игнорирует строки внутри fenced code blocks (```).
parse_changelog() {
    awk '
        /^```/ { in_code = !in_code; next }
        in_code { next }
        /^## \[/ {
            if (found) exit
            found = 1
            next
        }
        found && /^## \[/ { exit }
        found { print }
    ' "$CHANGELOG_FILE" | sed -e 's/^[[:space:]]*//' -e '/./,$!d' | awk '
        # Удаляем хвостовые пустые строки
        { lines[NR] = $0 }
        END {
            n = NR
            while (n > 0 && lines[n] == "") n--
            for (i = 1; i <= n; i++) print lines[i]
        }
    '
}

# Извлекаем versionName и versionCode из верхнего заголовка (вне code-блоков)
extract_top_header() {
    awk '
        /^```/ { in_code = !in_code; next }
        in_code { next }
        /^## \[/ { print; exit }
    ' "$CHANGELOG_FILE"
}

if [ "$MODE" = "onlyClient" ]; then
    # ── Только клиент ─────────────────────────────────────────────────────────

    # Проверяем что CHANGELOG.md существует и не пуст
    if [ ! -f "$CHANGELOG_FILE" ]; then
        echo "✗ Файл $CHANGELOG_FILE не найден"
        echo "  Создайте новую запись о версии перед деплоем (см. шаблон в файле)"
        exit 1
    fi

    VERSION_CODE=$(grep 'versionCode' app/build.gradle.kts | head -1 | sed 's/[^0-9]//g')
    VERSION_NAME=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
    echo "==> Версия для деплоя: $VERSION_NAME (code=$VERSION_CODE)"

    # Извлекаем changelog последней записи
    CHANGELOG=$(parse_changelog)
    if [ -z "$CHANGELOG" ]; then
        echo "✗ Не удалось извлечь changelog из $CHANGELOG_FILE"
        echo "  Убедитесь что верхняя запись соответствует шаблону:"
        echo "    ## [1.2.3] (123) — 2026-01-15"
        echo "    - изменение 1"
        echo "    - изменение 2"
        exit 1
    fi

    # Проверяем что versionName в build.gradle.kts совпадает с верхней записью CHANGELOG
    TOP_HEADER=$(extract_top_header)
    TOP_VERSION_NAME=$(echo "$TOP_HEADER" | sed 's/^## \[\(.*\)\] (.*/\1/')
    TOP_VERSION_CODE=$(echo "$TOP_HEADER" | sed 's/.*(\([0-9][0-9]*\)).*/\1/')
    if [ "$TOP_VERSION_NAME" != "$VERSION_NAME" ] || [ "$TOP_VERSION_CODE" != "$VERSION_CODE" ]; then
        echo "✗ Расхождение версий:"
        echo "    build.gradle.kts: $VERSION_NAME (code=$VERSION_CODE)"
        echo "    CHANGELOG.md:     $TOP_VERSION_NAME (code=$TOP_VERSION_CODE)"
        echo "  Обновите либо build.gradle.kts, либо верхнюю запись в CHANGELOG.md"
        exit 1
    fi

    echo ""
    echo "==> [1/4] Сборка APK..."
    ./gradlew assembleRelease --quiet

    echo ""
    echo "==> [2/4] Загрузка APK на сервер..."
    scp $SSH_OPTS "$APK_PATH" "$SERVER:$REMOTE_APK"
    echo "    Загружено: $SERVER:$REMOTE_APK"

    echo ""
    echo "==> [3/4] Регистрация версии в admin API..."
    # Передаём JSON через jq если доступен, иначе вручную с python
    if command -v jq >/dev/null 2>&1; then
        BODY=$(jq -nc \
            --argjson code "$VERSION_CODE" \
            --arg name "$VERSION_NAME" \
            --arg log "$CHANGELOG" \
            --arg url "$DOWNLOAD_URL" \
            '{versionCode: $code, versionName: $name, changelog: $log, downloadUrl: $url}')
    else
        BODY=$(python3 -c "
import json, sys
print(json.dumps({
    'versionCode': $VERSION_CODE,
    'versionName': '$VERSION_NAME',
    'changelog': '''$(echo "$CHANGELOG" | sed "s/'/\\\\'/g")''',
    'downloadUrl': '$DOWNLOAD_URL',
}))")
    fi

    HTTP_CODE=$(curl -sS -o /tmp/deploy_resp.json -w '%{http_code}' \
        -X POST "$ADMIN_API" \
        -H "Authorization: Bearer $ADMIN_SECRET" \
        -H "Content-Type: application/json" \
        -d "$BODY")
    if [ "$HTTP_CODE" != "200" ]; then
        echo "✗ Не удалось зарегистрировать версию (HTTP $HTTP_CODE):"
        cat /tmp/deploy_resp.json
        exit 1
    fi
    echo "    Версия зарегистрирована в БД"

    echo ""
    echo "==> [4/4] Готово!"
    echo "    Версия:    v$VERSION_NAME (code=$VERSION_CODE)"
    echo "    APK:       $DOWNLOAD_URL"
    echo "    Changelog:"
    echo "$CHANGELOG" | sed 's/^/      /'

else
    # ── Только сервер ─────────────────────────────────────────────────────────

    echo "==> [1/1] Деплой сервера (ветка '$BRANCH')..."
    ssh $SSH_OPTS "$SERVER" "bash $REMOTE_SCRIPT $BRANCH"

    echo ""
    echo "==> Деплой сервера завершён!"
fi
