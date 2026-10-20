CREATE TABLE roles (
    role_id BIGSERIAL PRIMARY KEY,
    role_name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE permissions (
    permission_id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES roles (role_id),
    permission_code VARCHAR(100) NOT NULL
);

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES roles (role_id),
    login VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_login TIMESTAMP
);

CREATE TABLE ad_accounts (
    account_id BIGSERIAL PRIMARY KEY,
    account_name VARCHAR(255) NOT NULL,
    platform VARCHAR(100) NOT NULL,
    connected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE campaigns (
    campaign_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES ad_accounts (account_id),
    campaign_name VARCHAR(255) NOT NULL,
    budget NUMERIC(12, 2) NOT NULL CHECK (budget >= 0),
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(50) NOT NULL CHECK (status IN ('PLANNED', 'ACTIVE', 'FINISHED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE campaign_access (
    access_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (user_id),
    campaign_id BIGINT NOT NULL REFERENCES campaigns (campaign_id),
    UNIQUE (user_id, campaign_id)
);

CREATE TABLE uploads (
    upload_id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES campaigns (campaign_id),
    file_name VARCHAR(255) NOT NULL,
    file_format VARCHAR(10) NOT NULL CHECK (file_format IN ('CSV', 'XLSX')),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (period_end >= period_start)
);

CREATE TABLE daily_metrics (
    metric_id BIGSERIAL PRIMARY KEY,
    upload_id BIGINT NOT NULL REFERENCES uploads (upload_id),
    metric_date DATE NOT NULL,
    cost NUMERIC(12, 2) NOT NULL CHECK (cost >= 0),
    revenue NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (revenue >= 0),
    impressions INTEGER NOT NULL CHECK (impressions >= 0),
    clicks INTEGER NOT NULL CHECK (clicks >= 0),
    UNIQUE (upload_id, metric_date)
);

CREATE TABLE ml_models (
    model_id BIGSERIAL PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL,
    model_version VARCHAR(50) NOT NULL,
    trained_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    quality_metric NUMERIC(6, 2),
    UNIQUE (model_name, model_version)
);

CREATE TABLE forecasts (
    forecast_id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES campaigns (campaign_id),
    model_id BIGINT NOT NULL REFERENCES ml_models (model_id),
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE forecast_params (
    param_id BIGSERIAL PRIMARY KEY,
    forecast_id BIGINT NOT NULL UNIQUE REFERENCES forecasts (forecast_id),
    horizon_days INTEGER NOT NULL CHECK (horizon_days > 0),
    confidence_level NUMERIC(4, 2) NOT NULL CHECK (confidence_level >= 0 AND confidence_level <= 1),
    use_seasonality BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE forecast_results (
    result_id BIGSERIAL PRIMARY KEY,
    forecast_id BIGINT NOT NULL UNIQUE REFERENCES forecasts (forecast_id),
    payback_months NUMERIC(6, 2),
    forecast_roi NUMERIC(8, 2),
    payback_probability NUMERIC(5, 2) CHECK (payback_probability IS NULL OR (payback_probability >= 0 AND payback_probability <= 1)),
    mape NUMERIC(6, 2)
);

CREATE TABLE export_history (
    log_id BIGSERIAL PRIMARY KEY,
    forecast_id BIGINT NOT NULL REFERENCES forecasts (forecast_id),
    export_format VARCHAR(10) NOT NULL CHECK (export_format IN ('PDF', 'XLSX')),
    export_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_permissions_role ON permissions (role_id);
CREATE INDEX idx_users_role ON users (role_id);
CREATE INDEX idx_campaigns_account ON campaigns (account_id);
CREATE INDEX idx_campaigns_status ON campaigns (status);
CREATE INDEX idx_campaign_access_user ON campaign_access (user_id);
CREATE INDEX idx_uploads_campaign ON uploads (campaign_id);
CREATE INDEX idx_daily_metrics_upload_date ON daily_metrics (upload_id, metric_date);
CREATE INDEX idx_daily_metrics_date ON daily_metrics (metric_date);
CREATE INDEX idx_forecasts_campaign ON forecasts (campaign_id);
CREATE INDEX idx_forecasts_model ON forecasts (model_id);
CREATE INDEX idx_forecasts_status ON forecasts (status);
CREATE INDEX idx_export_history_forecast ON export_history (forecast_id);

CREATE VIEW v_campaign_summary AS
SELECT
    c.campaign_id,
    c.campaign_name,
    c.status,
    c.budget,
    c.currency,
    a.platform,
    COUNT(DISTINCT u.upload_id) AS upload_count,
    COUNT(m.metric_id) AS days_count,
    COALESCE(SUM(m.cost), 0) AS total_cost,
    COALESCE(SUM(m.revenue), 0) AS total_revenue,
    CASE WHEN COALESCE(SUM(m.cost), 0) > 0
         THEN ROUND((SUM(m.revenue) - SUM(m.cost)) / SUM(m.cost) * 100, 2)
    END AS actual_roi,
    COALESCE(SUM(m.impressions), 0) AS total_impressions,
    COALESCE(SUM(m.clicks), 0) AS total_clicks
FROM campaigns c
JOIN ad_accounts a ON a.account_id = c.account_id
LEFT JOIN uploads u ON u.campaign_id = c.campaign_id
LEFT JOIN daily_metrics m ON m.upload_id = u.upload_id
GROUP BY c.campaign_id, c.campaign_name, c.status, c.budget, c.currency, a.platform;

CREATE VIEW v_forecast_stats AS
SELECT
    c.campaign_id,
    c.campaign_name,
    COUNT(f.forecast_id) AS forecast_count,
    MAX(f.started_at) AS last_forecast_at,
    latest.forecast_roi AS latest_forecast_roi,
    latest.payback_months AS latest_payback_months,
    latest.payback_probability AS latest_payback_probability
FROM campaigns c
LEFT JOIN forecasts f ON f.campaign_id = c.campaign_id
LEFT JOIN LATERAL (
    SELECT r.forecast_roi, r.payback_months, r.payback_probability
    FROM forecasts f2
    JOIN forecast_results r ON r.forecast_id = f2.forecast_id
    WHERE f2.campaign_id = c.campaign_id AND f2.status = 'COMPLETED'
    ORDER BY f2.started_at DESC
    LIMIT 1
) latest ON TRUE
GROUP BY c.campaign_id, c.campaign_name,
         latest.forecast_roi, latest.payback_months, latest.payback_probability;
