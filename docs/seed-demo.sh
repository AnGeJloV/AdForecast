#!/usr/bin/env bash
# AdForecast — демонстрационное наполнение.
#
# Что делает скрипт (идемпотентно, можно запускать повторно):
#   1. Создаёт двух пользователей по умолчанию (или обновляет им пароли):
#        маркетолог    angejlov / adforecastbsuir  (Станислав Ангелов)
#        администратор admin    / adforecastbsuir
#   2. Переносит все демо-кампании маркетологу angejlov, лишние тестовые
#      учётные записи удаляет (их записи аудита остаются, без автора).
#   3. Если БД пустая — генерирует синтетику (db/generator/generate.py)
#      и загружает её: ~800 кампаний, ~37 тыс. дневных показателей.
#   4. Создаёт ~12 прогнозов через живой API (завершённые/активные/
#      планируемые кампании — чтобы заполнить историю и «план против факта»).
#   5. Выгружает несколько отчётов XLSX/PDF (записи в истории экспорта).
#
# Запуск (из любого каталога, стек должен быть поднят):
#   bash docs/seed-demo.sh
#
# Требования: docker compose stack (db, backend, ml-service), curl;
# python — только если БД пустая и нужна генерация синтетики.

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
    $DB -1 < "$ROOT/db/generator/seed_data.sql" >/dev/null
    echo "   загружено кампаний: $($DB -t -A -c 'SELECT count(*) FROM campaigns')"
else
    echo "   кампании уже есть ($CAMPAIGNS) — генерация не нужна"
fi

# ── 3. Демо-данные принадлежат angejlov, лишние учётки удаляем ───────────
say "Привязка кампаний к angejlov и очистка лишних учётных записей"
$DB <<'SQL'
INSERT INTO campaign_access (user_id, campaign_id)
SELECT dst.user_id, src.campaign_id
FROM campaign_access src
JOIN users dst ON dst.login = 'angejlov'
WHERE src.user_id <> dst.user_id
ON CONFLICT (user_id, campaign_id) DO NOTHING;

DELETE FROM campaign_access
WHERE user_id <> (SELECT user_id FROM users WHERE login = 'angejlov');

UPDATE audit_actions SET user_id = NULL
WHERE user_id NOT IN (SELECT user_id FROM users WHERE login IN ('admin', 'angejlov'));

DELETE FROM users WHERE login NOT IN ('admin', 'angejlov');
SQL
echo "   кампаний у angejlov: $($DB -t -A -c "SELECT count(*) FROM campaign_access ca JOIN users u ON u.user_id = ca.user_id WHERE u.login = 'angejlov'")"

# ── 4. Прогнозы через живой API ──────────────────────────────────────────
login_token() {
    curl -s -X POST "$API/auth/login" -H "Content-Type: application/json" \
        -d "{\"login\":\"$1\",\"password\":\"$DEFAULT_PASSWORD\"}" \
        | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

TOKEN=$(login_token angejlov)
[ -n "$TOKEN" ] || fail "не удалось войти как angejlov — проверьте, что backend поднят"

FORECASTS=$($DB -t -A -c "SELECT count(*) FROM forecasts")
if [ "$FORECASTS" -ge 10 ]; then
    echo "── Прогнозы уже есть ($FORECASTS) — пропускаю создание"
else
    say "Создание прогнозов через API (маркетолог)"
    ANGEJLOV_ID=$($DB -t -A -c "SELECT user_id FROM users WHERE login = 'angejlov'")
    run_forecast() { # campaign_id horizon start_month
        local result
        result=$(curl -s -X POST "$API/campaigns/$1/forecasts" \
            -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
            -d "{\"horizonDays\":$2,\"confidenceLevel\":0.9,\"useSeasonality\":true,\"startMonth\":$3}")
        if echo "$result" | grep -q '"status":"COMPLETED"'; then
            echo "   ✓ кампания $1: горизонт $2 дн. — готов"
        else
            echo "   ✕ кампания $1: $(echo "$result" | head -c 120)"
        fi
        sleep 0.3
    }

    # Завершённые: 4 с горизонтом ≈ длительности (сопоставимые в «план против факт»),
    # 2 с завышенным горизонтом (появится «несопоставимо»)
    INDEX=0
    $DB -t -A -F'|' -c "
        SELECT v.campaign_id, v.days_count
        FROM v_campaign_summary v
        WHERE v.status = 'FINISHED' AND v.days_count >= 30
          AND v.campaign_id IN (SELECT campaign_id FROM campaign_access WHERE user_id = $ANGEJLOV_ID)
        ORDER BY v.days_count DESC
        LIMIT 6" | while IFS='|' read -r cid days; do
        INDEX=$((INDEX + 1))
        if [ "$INDEX" -le 4 ]; then
            run_forecast "$cid" "$days" 10
        else
            run_forecast "$cid" "$((days + 90))" 10
        fi
    done

    # Активные — пересчёт по текущим данным
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
        run_forecast "$cid" 90 "$MONTH"
        MONTH=$(( MONTH % 12 + 1 ))
    done
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
        curl -s "$API/forecasts/$fid/export/xlsx" -H "Authorization: Bearer $TOKEN" > /dev/null
        N=$((N + 1))
        [ "$N" -le 2 ] && curl -s "$API/forecasts/$fid/export/pdf" -H "Authorization: Bearer $TOKEN" > /dev/null
    done
    echo "   выгружено отчётов: $($DB -t -A -c 'SELECT count(*) FROM export_history')"
fi

# ── Итог ─────────────────────────────────────────────────────────────────
echo
echo "Готово. Вход: http://localhost:${APP_PORT}"
echo "  маркетолог:    angejlov / $DEFAULT_PASSWORD  (Станислав Ангелов)"
echo "  администратор: admin / $DEFAULT_PASSWORD"
echo "  кампаний: $($DB -t -A -c 'SELECT count(*) FROM campaigns'), метрик: $($DB -t -A -c 'SELECT count(*) FROM daily_metrics'), прогнозов: $($DB -t -A -c 'SELECT count(*) FROM forecasts'), отчётов: $($DB -t -A -c 'SELECT count(*) FROM export_history')"
