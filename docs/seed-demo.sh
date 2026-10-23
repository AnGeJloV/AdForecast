#!/usr/bin/env bash
# Демонстрационное наполнение AdForecast: пользователи по умолчанию (angejlov, admin /
# adforecastbsuir), демо-кампании у маркетолога, прогнозы и история экспорта.
# Идемпотентно; запускать при поднятом стеке: bash docs/seed-demo.sh

set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && (pwd -W 2>/dev/null || pwd))"
export COMPOSE_FILE="$ROOT/deploy/docker-compose.yml"
COMPOSE="docker compose"

# Учётные данные БД — из deploy/.env, если есть
POSTGRES_USER=adforecast
if [ -f "$ROOT/deploy/.env" ]; then
    # shellcheck disable=SC1091
    . "$ROOT/deploy/.env"
fi
DB="$COMPOSE exec -T db psql -U ${POSTGRES_USER:-adforecast} -d adforecast -v ON_ERROR_STOP=1"
APP_PORT="${APP_PORT:-80}"
API="http://localhost:${APP_PORT}/api"

DEFAULT_PASSWORD='adforecastbsuir'

say() { echo "── $*"; }
fail() { echo "ОШИБКА: $*" >&2; exit 1; }

login_token() {
    curl -s -X POST "$API/auth/login" -H "Content-Type: application/json" \
        -d "{\"login\":\"$1\",\"password\":\"$DEFAULT_PASSWORD\"}" \
        | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

# ── 0. Проверки окружения ────────────────────────────────────────────────
say "Проверка стека"
RUNNING=$($COMPOSE ps --status running --services 2>/dev/null || true)
for service in db backend ml-service; do
    echo "$RUNNING" | grep -qx "$service" || fail "сервис '$service' не запущен — сначала выполните: cd deploy && docker compose up -d"
done
$COMPOSE exec -T db pg_isready -U "${POSTGRES_USER:-adforecast}" -d adforecast >/dev/null \
    || fail "PostgreSQL не отвечает"
echo "   стек в порядке"

# ── 1. Пользователи по умолчанию ─────────────────────────────────────────
say "Пользователи по умолчанию (angejlov, admin)"
$DB <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (role_id, login, email, password_hash, full_name, is_active)
SELECT r.role_id, 'admin', 'admin@adforecast.by',
       crypt('adforecastbsuir', gen_salt('bf')), 'admin', TRUE
FROM roles r WHERE r.role_name = 'администратор'
ON CONFLICT (login) DO UPDATE SET
    role_id = EXCLUDED.role_id,
    email = EXCLUDED.email,
    full_name = EXCLUDED.full_name,
    password_hash = crypt('adforecastbsuir', gen_salt('bf')),
    is_active = TRUE;

INSERT INTO users (role_id, login, email, password_hash, full_name, is_active)
SELECT r.role_id, 'angejlov', 'angejlov@adforecast.by',
       crypt('adforecastbsuir', gen_salt('bf')), 'Станислав Ангелов', TRUE
FROM roles r WHERE r.role_name = 'маркетолог'
ON CONFLICT (login) DO UPDATE SET
    role_id = EXCLUDED.role_id,
    email = EXCLUDED.email,
    full_name = EXCLUDED.full_name,
    password_hash = crypt('adforecastbsuir', gen_salt('bf')),
    is_active = TRUE;
SQL
echo "   учётные записи созданы/обновлены"

# ── 2. Синтетические данные, если БД пустая ──────────────────────────────
CAMPAIGNS=$($DB -t -A -c "SELECT count(*) FROM campaigns")
if [ "$CAMPAIGNS" = "0" ]; then
    say "БД пустая — генерация синтетики (800 кампаний, ~37 тыс. метрик)"
    PYTHON=$(command -v python || command -v py || command -v python3 || true)
    [ -n "$PYTHON" ] || fail "БД пустая, а python не найден — установите Python или загрузите seed вручную (db/generator/README.md)"
    (cd "$ROOT/db/generator" && "$PYTHON" generate.py)
    say "Загрузка seed_data.sql"
    $DB -c "TRUNCATE daily_metrics, uploads, campaign_access, campaigns, ad_accounts,
                     forecasts, forecast_params, forecast_results, export_history, ml_models
            RESTART IDENTITY CASCADE" >/dev/null
    # генератор пишет доступы для user_id = 2 — следим, чтобы такой был
    $DB <<'SQL' >/dev/null
INSERT INTO users (user_id, role_id, organization_id, login, email, password_hash, full_name, is_active)
SELECT 2, r.role_id, 1, 'seed_placeholder', 'placeholder@local', 'x', '', FALSE
FROM roles r WHERE r.role_name = 'маркетолог'
ON CONFLICT (user_id) DO NOTHING;
SQL
    $DB -1 < "$ROOT/db/generator/seed_data.sql" >/dev/null
    echo "   загружено кампаний: $($DB -t -A -c 'SELECT count(*) FROM campaigns')"
else
    echo "   кампании уже есть ($CAMPAIGNS) — генерация не нужна"
fi

# ── 2б. Общая модель — платформенный ресурс, обучает администратор ───────
MODELS=$($DB -t -A -c "SELECT count(*) FROM ml_models WHERE organization_id IS NULL")
if [ "$MODELS" = "0" ]; then
    say "Начальное обучение общей модели (админский API)"
    ATOKEN=$(login_token admin)
    [ -n "$ATOKEN" ] || fail "не удалось войти как admin — проверьте, что backend поднят"
    TASK=$(curl -s -X POST "$API/admin/training/start" -H "Authorization: Bearer $ATOKEN" \
        -H "Content-Type: application/json" -d '{}' | sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p')
    [ -n "$TASK" ] || fail "не удалось запустить обучение общей модели"
    for _ in $(seq 1 60); do
        sleep 5
        STATUS=$(curl -s "$API/admin/training/tasks/$TASK" -H "Authorization: Bearer $ATOKEN")
        echo "$STATUS" | grep -q '"COMPLETED"' && break
        if echo "$STATUS" | grep -q '"FAILED"'; then
            fail "обучение общей модели завершилось с ошибкой: $(echo "$STATUS" | head -c 200)"
        fi
    done
    echo "   общая модель: $($DB -t -A -c "SELECT model_version FROM ml_models WHERE organization_id IS NULL AND is_active")"
else
    echo "── Общая модель уже обучена ($MODELS) — пропускаю"
fi

# ── 3. Демо-данные принадлежат angejlov, лишние учётки удаляем ───────────
# Важно: чужие организации, кампании и учётные записи скрипт не трогает.
say "Привязка демо-кампаний к angejlov"
$DB <<'SQL'
SELECT setval('campaign_access_access_id_seq', COALESCE((SELECT max(access_id) FROM campaign_access), 1));

INSERT INTO campaign_access (user_id, campaign_id)
SELECT dst.user_id, src.campaign_id
FROM campaign_access src
JOIN users dst ON dst.login = 'angejlov'
JOIN campaigns c ON c.campaign_id = src.campaign_id
WHERE c.organization_id = 1 AND src.user_id <> dst.user_id
ON CONFLICT (user_id, campaign_id) DO NOTHING;

DELETE FROM campaign_access ca
USING campaigns c
WHERE ca.campaign_id = c.campaign_id AND c.organization_id = 1
  AND ca.user_id <> (SELECT user_id FROM users WHERE login = 'angejlov');

DELETE FROM users WHERE login = 'seed_placeholder';
SQL
echo "   кампаний у angejlov: $($DB -t -A -c "SELECT count(*) FROM campaign_access ca JOIN users u ON u.user_id = ca.user_id WHERE u.login = 'angejlov'")"

# ── 4. Прогнозы через живой API ──────────────────────────────────────────
TOKEN=$(login_token angejlov)
[ -n "$TOKEN" ] || fail "не удалось войти как angejlov — проверьте, что backend поднят"

FORECASTS=$($DB -t -A -c "SELECT count(*) FROM forecasts")
if [ "$FORECASTS" -ge 10 ]; then
    echo "── Прогнозы уже есть ($FORECASTS) — пропускаю создание"
else
    say "Создание прогнозов через API (маркетолог)"
    ANGEJLOV_ID=$($DB -t -A -c "SELECT user_id FROM users WHERE login = 'angejlov'")
    run_forecast() { # campaign_id duration_days start_month -> 0 при успехе
        local result
        result=$(curl -s -X POST "$API/campaigns/$1/forecasts" \
            -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
            -d "{\"durationDays\":$2,\"startMonth\":$3}")
        sleep 0.3
        if echo "$result" | grep -q '"status":"COMPLETED"'; then
            echo "   ✓ кампания $1: длительность $2 дн. — готов"
            return 0
        fi
        echo "   ✕ кампания $1: $(echo "$result" | head -c 120)"
        return 1
    }

    # Активные, которые затем «завершаем»: их прогнозы (сделанные по ходу кампании)
    # становятся строками «план против факта». Первые 4 — длительность = дни данных + 30
    # (сопоставимо), следующие 2 — сильно больше (несопоставимо).
    FLIP_IDS=""
    INDEX=0
    $DB -t -A -F'|' -c "
        SELECT v.campaign_id, v.days_count
        FROM v_campaign_summary v
        WHERE v.status = 'ACTIVE' AND v.days_count >= 20
          AND v.campaign_id IN (SELECT campaign_id FROM campaign_access WHERE user_id = $ANGEJLOV_ID)
        ORDER BY v.days_count DESC
        LIMIT 6" | while IFS='|' read -r cid days; do
        INDEX=$((INDEX + 1))
        if [ "$INDEX" -le 4 ]; then
            run_forecast "$cid" "$((days + 30))" 10 && echo "$cid" >> /tmp/adforecast_flip_ids
        else
            run_forecast "$cid" "$((days + 90))" 10 && echo "$cid" >> /tmp/adforecast_flip_ids
        fi
    done
    FLIP_IDS=$(cat /tmp/adforecast_flip_ids 2>/dev/null | tr '\n' ' ')
    rm -f /tmp/adforecast_flip_ids
    if [ -n "$FLIP_IDS" ]; then
        ID_LIST=$(echo "$FLIP_IDS" | tr ' ' ',' | sed 's/,$//')
        $DB -c "UPDATE campaigns SET status = 'FINISHED' WHERE campaign_id IN ($ID_LIST)" >/dev/null
        echo "   завершены кампании с прогнозами (для «план против факта»): $FLIP_IDS"
    fi

    # Оставшиеся активные — прогноз по текущим данным
    $DB -t -A -c "
        SELECT campaign_id FROM campaigns
        WHERE status = 'ACTIVE'
          AND campaign_id IN (SELECT campaign_id FROM campaign_access WHERE user_id = $ANGEJLOV_ID)
        ORDER BY campaign_id LIMIT 3" | while read -r cid; do
        run_forecast "$cid" 90 10
    done

    # Планируемые — прогноз до запуска, разные месяцы старта
    MONTH=11
    $DB -t -A -c "
        SELECT campaign_id FROM campaigns
        WHERE status = 'PLANNED'
          AND campaign_id IN (SELECT campaign_id FROM campaign_access WHERE user_id = $ANGEJLOV_ID)
        ORDER BY campaign_id LIMIT 3" | while read -r cid; do
        run_forecast "$cid" 60 "$MONTH"
        MONTH=$(( MONTH % 12 + 1 ))
    done
fi

# ── 4б. Модель организации обучается сама (без администратора) ───────────
say "Ожидание автоматического обучения модели организации"
OWN=0
for _ in $(seq 1 48); do
    OWN=$($DB -t -A -c "SELECT count(*) FROM ml_models WHERE organization_id IS NOT NULL AND is_active AND training_signature IS NOT NULL")
    [ "$OWN" != "0" ] && break
    # ленивый триггер срабатывает на запросе прогноза
    CID=$($DB -t -A -c "SELECT campaign_id FROM campaigns WHERE status = 'PLANNED' ORDER BY campaign_id LIMIT 1")
    curl -s -X POST "$API/campaigns/$CID/forecasts" -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" -d '{"durationDays":60,"startMonth":12}' > /dev/null
    sleep 5
done
if [ "$OWN" != "0" ]; then
    echo "   своя модель: $($DB -t -A -c "SELECT model_name || ' ' || model_version FROM ml_models WHERE organization_id IS NOT NULL AND is_active")"
    # контрольный прогноз после появления своей модели — должен ею воспользоваться
    CID=$($DB -t -A -c "SELECT campaign_id FROM campaigns WHERE status = 'PLANNED' ORDER BY campaign_id LIMIT 1")
    RESULT=$(curl -s -X POST "$API/campaigns/$CID/forecasts" -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" -d '{"durationDays":60,"startMonth":12}')
    echo "   контрольный прогноз: $(echo "$RESULT" | sed -n 's/.*"verdict":"\([^"]*\)".*/\1/p'), своя модель $(echo "$RESULT" | sed -n 's/.*"ownModelVersion":"\([^"]*\)".*/\1/p'), вес $(echo "$RESULT" | sed -n 's/.*"ownWeight":\([0-9.]*\).*/\1/p')"
    echo "   основание: $(echo "$RESULT" | sed -n 's/.*"basis":"\([^"]*\)".*/\1/p')"
else
    echo "   ⚠ своя модель пока не готова — прогнозы работают на общей"
fi

# ── 5. Отчёты в истории экспорта ─────────────────────────────────────────
EXPORTS=$($DB -t -A -c "SELECT count(*) FROM export_history")
if [ "$EXPORTS" -ge 3 ]; then
    echo "── Отчёты уже выгружались ($EXPORTS) — пропускаю"
else
    say "Выгрузка отчётов XLSX/PDF (история экспорта)"
    IDS=$($DB -t -A -c "SELECT forecast_id FROM forecasts WHERE status = 'COMPLETED' ORDER BY forecast_id LIMIT 3")
    N=0
    for fid in $IDS; do
        # без -o /dev/null: при MSYS_NO_PATHCONV=1 путь не конвертируется в NUL
        curl -s "$API/forecasts/$fid/export/xlsx" -H "Authorization: Bearer $TOKEN" > /dev/null || true
        if [ "$N" -lt 2 ]; then
            curl -s "$API/forecasts/$fid/export/pdf" -H "Authorization: Bearer $TOKEN" > /dev/null || true
        fi
        N=$((N + 1))
    done
    echo "   выгружено отчётов: $($DB -t -A -c 'SELECT count(*) FROM export_history')"
fi

# ── Итог ─────────────────────────────────────────────────────────────────
echo
echo "Готово. Вход: http://localhost:${APP_PORT}"
echo "  маркетолог:    angejlov / $DEFAULT_PASSWORD  (Станислав Ангелов)"
echo "  администратор: admin / $DEFAULT_PASSWORD"
echo "  кампаний: $($DB -t -A -c 'SELECT count(*) FROM campaigns'), метрик: $($DB -t -A -c 'SELECT count(*) FROM daily_metrics'), прогнозов: $($DB -t -A -c 'SELECT count(*) FROM forecasts'), отчётов: $($DB -t -A -c 'SELECT count(*) FROM export_history')"
