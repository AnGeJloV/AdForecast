-- Организация как граница данных: пользователи, кабинеты и кампании принадлежат
-- организации; модели тоже (NULL = общая модель). Прогнозы v2: честный интервал
-- вместо «вероятности», вердикт, снимок наблюдаемого состояния.

CREATE TABLE organizations (
    organization_id BIGSERIAL PRIMARY KEY,
    organization_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO organizations (organization_id, organization_name)
VALUES (1, 'Демо-организация');
SELECT setval('organizations_organization_id_seq', 1);

ALTER TABLE users ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 REFERENCES organizations (organization_id);
ALTER TABLE ad_accounts ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 REFERENCES organizations (organization_id);
ALTER TABLE campaigns ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 REFERENCES organizations (organization_id);

ALTER TABLE ml_models ADD COLUMN organization_id BIGINT REFERENCES organizations (organization_id);

CREATE INDEX idx_users_organization ON users (organization_id);
CREATE INDEX idx_ad_accounts_organization ON ad_accounts (organization_id);
CREATE INDEX idx_campaigns_organization ON campaigns (organization_id);
CREATE INDEX idx_ml_models_organization ON ml_models (organization_id);

-- параметры прогноза: полная длительность вместо «горизонта» + снимок истории
ALTER TABLE forecast_params RENAME COLUMN horizon_days TO duration_days;
ALTER TABLE forecast_params ADD COLUMN start_month INTEGER;
ALTER TABLE forecast_params ADD COLUMN hist_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE forecast_params ADD COLUMN hist_cost NUMERIC(14, 2);
ALTER TABLE forecast_params ADD COLUMN hist_revenue NUMERIC(14, 2);

-- результаты прогноза v2
ALTER TABLE forecast_results ADD COLUMN verdict VARCHAR(20);
ALTER TABLE forecast_results ADD COLUMN payback_ratio NUMERIC(8, 4);
ALTER TABLE forecast_results ADD COLUMN interval_lower NUMERIC(8, 4);
ALTER TABLE forecast_results ADD COLUMN interval_upper NUMERIC(8, 4);
ALTER TABLE forecast_results ADD COLUMN agreement NUMERIC(4, 2);
ALTER TABLE forecast_results ADD COLUMN payback_days NUMERIC(7, 1);
ALTER TABLE forecast_results ADD COLUMN expected_profit NUMERIC(14, 2);
ALTER TABLE forecast_results ADD COLUMN budget_min NUMERIC(12, 2);
ALTER TABLE forecast_results ADD COLUMN budget_max NUMERIC(12, 2);
ALTER TABLE forecast_results ADD COLUMN own_weight NUMERIC(4, 3);
ALTER TABLE forecast_results ADD COLUMN own_model_id BIGINT REFERENCES ml_models (model_id);

DROP VIEW v_forecast_stats;

ALTER TABLE forecast_results DROP COLUMN payback_probability;

CREATE OR REPLACE VIEW v_forecast_stats AS
SELECT
    c.campaign_id,
    c.campaign_name,
    COUNT(f.forecast_id) AS forecast_count,
    MAX(f.started_at) AS last_forecast_at,
    latest.forecast_roi AS latest_forecast_roi,
    latest.payback_ratio AS latest_payback_ratio,
    latest.payback_months AS latest_payback_months,
    latest.verdict AS latest_verdict
FROM campaigns c
LEFT JOIN forecasts f ON f.campaign_id = c.campaign_id
LEFT JOIN LATERAL (
    SELECT r.forecast_roi, r.payback_ratio, r.payback_months, r.verdict
    FROM forecasts f2
    JOIN forecast_results r ON r.forecast_id = f2.forecast_id
    WHERE f2.campaign_id = c.campaign_id AND f2.status = 'COMPLETED'
    ORDER BY f2.started_at DESC
    LIMIT 1
) latest ON TRUE
GROUP BY c.campaign_id, c.campaign_name,
         latest.forecast_roi, latest.payback_ratio, latest.payback_months, latest.verdict;
