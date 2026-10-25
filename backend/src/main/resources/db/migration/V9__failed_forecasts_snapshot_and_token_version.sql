-- Неудачный прогноз существует как запись истории, даже если модель ещё не выбрана:
-- модель назначается только после успешного расчёта.
ALTER TABLE forecasts ALTER COLUMN model_id DROP NOT NULL;

-- Снимок денежных параметров кампании на момент прогноза: после изменения бюджета
-- или валюты карточка и отчёт должны показывать те же числа, что и при расчёте.
ALTER TABLE forecast_params ADD COLUMN budget NUMERIC(12, 2);
ALTER TABLE forecast_params ADD COLUMN currency VARCHAR(10);

-- Версия токена пользователя: выход из системы и смена роли/блокировка отзывают
-- ранее выданные JWT, не дожидаясь истечения срока действия.
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
