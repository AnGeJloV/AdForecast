CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE TABLE ad_accounts (
    account_id bigint NOT NULL,
    account_name character varying(255) NOT NULL,
    platform character varying(100) NOT NULL,
    connected_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    organization_id bigint DEFAULT 1 NOT NULL
);
CREATE SEQUENCE ad_accounts_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE ad_accounts_account_id_seq OWNED BY ad_accounts.account_id;
CREATE TABLE audit_actions (
    log_id bigint NOT NULL,
    user_id bigint,
    action character varying(100) NOT NULL,
    object_type character varying(50),
    object_id bigint,
    details character varying(1000),
    action_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ip character varying(45)
);
CREATE SEQUENCE audit_actions_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE audit_actions_log_id_seq OWNED BY audit_actions.log_id;
CREATE TABLE campaign_access (
    access_id bigint NOT NULL,
    user_id bigint NOT NULL,
    campaign_id bigint NOT NULL
);
CREATE SEQUENCE campaign_access_access_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE campaign_access_access_id_seq OWNED BY campaign_access.access_id;
CREATE TABLE campaigns (
    campaign_id bigint NOT NULL,
    account_id bigint NOT NULL,
    campaign_name character varying(255) NOT NULL,
    budget numeric(12,2) NOT NULL,
    currency character varying(10) NOT NULL,
    status character varying(50) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    in_training boolean DEFAULT true NOT NULL,
    is_test boolean DEFAULT false NOT NULL,
    organization_id bigint DEFAULT 1 NOT NULL,
    CONSTRAINT campaigns_budget_check CHECK ((budget >= (0)::numeric)),
    CONSTRAINT campaigns_status_check CHECK (((status)::text = ANY ((ARRAY['PLANNED'::character varying, 'ACTIVE'::character varying, 'FINISHED'::character varying])::text[])))
);
CREATE SEQUENCE campaigns_campaign_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE campaigns_campaign_id_seq OWNED BY campaigns.campaign_id;
CREATE TABLE daily_metrics (
    metric_id bigint NOT NULL,
    upload_id bigint NOT NULL,
    metric_date date NOT NULL,
    cost numeric(12,2) NOT NULL,
    revenue numeric(12,2) DEFAULT 0 NOT NULL,
    impressions integer NOT NULL,
    clicks integer NOT NULL,
    CONSTRAINT daily_metrics_clicks_check CHECK ((clicks >= 0)),
    CONSTRAINT daily_metrics_cost_check CHECK ((cost >= (0)::numeric)),
    CONSTRAINT daily_metrics_impressions_check CHECK ((impressions >= 0)),
    CONSTRAINT daily_metrics_revenue_check CHECK ((revenue >= (0)::numeric))
);
CREATE SEQUENCE daily_metrics_metric_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE daily_metrics_metric_id_seq OWNED BY daily_metrics.metric_id;
CREATE TABLE export_history (
    log_id bigint NOT NULL,
    forecast_id bigint NOT NULL,
    export_format character varying(10) NOT NULL,
    export_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT export_history_export_format_check CHECK (((export_format)::text = ANY ((ARRAY['PDF'::character varying, 'XLSX'::character varying])::text[])))
);
CREATE SEQUENCE export_history_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE export_history_log_id_seq OWNED BY export_history.log_id;
CREATE TABLE forecast_params (
    param_id bigint NOT NULL,
    forecast_id bigint NOT NULL,
    duration_days integer CONSTRAINT forecast_params_horizon_days_not_null NOT NULL,
    confidence_level numeric(4,2) NOT NULL,
    use_seasonality boolean DEFAULT false NOT NULL,
    start_month integer,
    hist_days integer DEFAULT 0 NOT NULL,
    hist_cost numeric(14,2),
    hist_revenue numeric(14,2),
    budget numeric(12,2),
    currency character varying(10),
    CONSTRAINT forecast_params_confidence_level_check CHECK (((confidence_level >= (0)::numeric) AND (confidence_level <= (1)::numeric))),
    CONSTRAINT forecast_params_horizon_days_check CHECK ((duration_days > 0))
);
CREATE SEQUENCE forecast_params_param_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE forecast_params_param_id_seq OWNED BY forecast_params.param_id;
CREATE TABLE forecast_results (
    result_id bigint NOT NULL,
    forecast_id bigint NOT NULL,
    payback_months numeric(6,2),
    forecast_roi numeric(8,2),
    mape numeric(6,2),
    verdict character varying(20),
    payback_ratio numeric(8,4),
    interval_lower numeric(8,4),
    interval_upper numeric(8,4),
    agreement numeric(4,2),
    payback_days numeric(7,1),
    expected_profit numeric(14,2),
    budget_min numeric(12,2),
    budget_max numeric(12,2),
    own_weight numeric(4,3),
    own_model_id bigint,
    budget_any boolean DEFAULT false NOT NULL,
    model_scope character varying(10) DEFAULT 'BLEND'::character varying NOT NULL,
    day_budget_min numeric(12,2),
    day_budget_max numeric(12,2),
    data_note character varying(255)
);
CREATE SEQUENCE forecast_results_result_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE forecast_results_result_id_seq OWNED BY forecast_results.result_id;
CREATE TABLE forecasts (
    forecast_id bigint NOT NULL,
    campaign_id bigint NOT NULL,
    model_id bigint,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status character varying(50) NOT NULL,
    CONSTRAINT forecasts_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);
CREATE SEQUENCE forecasts_forecast_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE forecasts_forecast_id_seq OWNED BY forecasts.forecast_id;
CREATE TABLE ml_models (
    model_id bigint NOT NULL,
    model_name character varying(255) NOT NULL,
    model_version character varying(50) NOT NULL,
    trained_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    quality_metric numeric(6,2),
    is_active boolean DEFAULT false NOT NULL,
    organization_id bigint,
    training_signature character varying(120)
);
CREATE SEQUENCE ml_models_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE ml_models_model_id_seq OWNED BY ml_models.model_id;
CREATE TABLE organizations (
    organization_id bigint NOT NULL,
    organization_name character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE SEQUENCE organizations_organization_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE organizations_organization_id_seq OWNED BY organizations.organization_id;
CREATE TABLE permissions (
    permission_id bigint NOT NULL,
    role_id bigint NOT NULL,
    permission_code character varying(100) NOT NULL
);
CREATE SEQUENCE permissions_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE permissions_permission_id_seq OWNED BY permissions.permission_id;
CREATE TABLE roles (
    role_id bigint NOT NULL,
    role_name character varying(100) NOT NULL
);
CREATE SEQUENCE roles_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE roles_role_id_seq OWNED BY roles.role_id;
CREATE TABLE uploads (
    upload_id bigint NOT NULL,
    campaign_id bigint NOT NULL,
    file_name character varying(255) NOT NULL,
    file_format character varying(10) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uploads_check CHECK ((period_end >= period_start)),
    CONSTRAINT uploads_file_format_check CHECK (((file_format)::text = ANY ((ARRAY['CSV'::character varying, 'XLSX'::character varying])::text[])))
);
CREATE SEQUENCE uploads_upload_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE uploads_upload_id_seq OWNED BY uploads.upload_id;
CREATE TABLE users (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    login character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    last_login timestamp without time zone,
    full_name character varying(255) DEFAULT ''::character varying NOT NULL,
    organization_id bigint DEFAULT 1 NOT NULL,
    token_version integer DEFAULT 0 NOT NULL
);
CREATE SEQUENCE users_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE users_user_id_seq OWNED BY users.user_id;
CREATE VIEW v_campaign_summary AS
 SELECT c.campaign_id,
    c.campaign_name,
    c.status,
    c.budget,
    c.currency,
    a.platform,
    count(DISTINCT u.upload_id) AS upload_count,
    count(m.metric_id) AS days_count,
    COALESCE(sum(m.cost), (0)::numeric) AS total_cost,
    COALESCE(sum(m.revenue), (0)::numeric) AS total_revenue,
        CASE
            WHEN (COALESCE(sum(m.cost), (0)::numeric) > (0)::numeric) THEN round((((sum(m.revenue) - sum(m.cost)) / sum(m.cost)) * (100)::numeric), 2)
            ELSE NULL::numeric
        END AS actual_roi,
    COALESCE(sum(m.impressions), (0)::bigint) AS total_impressions,
    COALESCE(sum(m.clicks), (0)::bigint) AS total_clicks
   FROM (((campaigns c
     JOIN ad_accounts a ON ((a.account_id = c.account_id)))
     LEFT JOIN uploads u ON ((u.campaign_id = c.campaign_id)))
     LEFT JOIN daily_metrics m ON ((m.upload_id = u.upload_id)))
  GROUP BY c.campaign_id, c.campaign_name, c.status, c.budget, c.currency, a.platform;
CREATE VIEW v_forecast_stats AS
 SELECT c.campaign_id,
    c.campaign_name,
    count(f.forecast_id) AS forecast_count,
    max(f.started_at) AS last_forecast_at,
    latest.forecast_roi AS latest_forecast_roi,
    latest.payback_ratio AS latest_payback_ratio,
    latest.payback_months AS latest_payback_months,
    latest.verdict AS latest_verdict
   FROM ((campaigns c
     LEFT JOIN forecasts f ON ((f.campaign_id = c.campaign_id)))
     LEFT JOIN LATERAL ( SELECT r.forecast_roi,
            r.payback_ratio,
            r.payback_months,
            r.verdict
           FROM (forecasts f2
             JOIN forecast_results r ON ((r.forecast_id = f2.forecast_id)))
          WHERE ((f2.campaign_id = c.campaign_id) AND ((f2.status)::text = 'COMPLETED'::text))
          ORDER BY f2.started_at DESC
         LIMIT 1) latest ON (true))
  GROUP BY c.campaign_id, c.campaign_name, latest.forecast_roi, latest.payback_ratio, latest.payback_months, latest.verdict;
ALTER TABLE ONLY ad_accounts ALTER COLUMN account_id SET DEFAULT nextval('ad_accounts_account_id_seq'::regclass);
ALTER TABLE ONLY audit_actions ALTER COLUMN log_id SET DEFAULT nextval('audit_actions_log_id_seq'::regclass);
ALTER TABLE ONLY campaign_access ALTER COLUMN access_id SET DEFAULT nextval('campaign_access_access_id_seq'::regclass);
ALTER TABLE ONLY campaigns ALTER COLUMN campaign_id SET DEFAULT nextval('campaigns_campaign_id_seq'::regclass);
ALTER TABLE ONLY daily_metrics ALTER COLUMN metric_id SET DEFAULT nextval('daily_metrics_metric_id_seq'::regclass);
ALTER TABLE ONLY export_history ALTER COLUMN log_id SET DEFAULT nextval('export_history_log_id_seq'::regclass);
ALTER TABLE ONLY forecast_params ALTER COLUMN param_id SET DEFAULT nextval('forecast_params_param_id_seq'::regclass);
ALTER TABLE ONLY forecast_results ALTER COLUMN result_id SET DEFAULT nextval('forecast_results_result_id_seq'::regclass);
ALTER TABLE ONLY forecasts ALTER COLUMN forecast_id SET DEFAULT nextval('forecasts_forecast_id_seq'::regclass);
ALTER TABLE ONLY ml_models ALTER COLUMN model_id SET DEFAULT nextval('ml_models_model_id_seq'::regclass);
ALTER TABLE ONLY organizations ALTER COLUMN organization_id SET DEFAULT nextval('organizations_organization_id_seq'::regclass);
ALTER TABLE ONLY permissions ALTER COLUMN permission_id SET DEFAULT nextval('permissions_permission_id_seq'::regclass);
ALTER TABLE ONLY roles ALTER COLUMN role_id SET DEFAULT nextval('roles_role_id_seq'::regclass);
ALTER TABLE ONLY uploads ALTER COLUMN upload_id SET DEFAULT nextval('uploads_upload_id_seq'::regclass);
ALTER TABLE ONLY users ALTER COLUMN user_id SET DEFAULT nextval('users_user_id_seq'::regclass);
ALTER TABLE ONLY ad_accounts
    ADD CONSTRAINT ad_accounts_pkey PRIMARY KEY (account_id);
ALTER TABLE ONLY audit_actions
    ADD CONSTRAINT audit_actions_pkey PRIMARY KEY (log_id);
ALTER TABLE ONLY campaign_access
    ADD CONSTRAINT campaign_access_pkey PRIMARY KEY (access_id);
ALTER TABLE ONLY campaign_access
    ADD CONSTRAINT campaign_access_user_id_campaign_id_key UNIQUE (user_id, campaign_id);
ALTER TABLE ONLY campaigns
    ADD CONSTRAINT campaigns_pkey PRIMARY KEY (campaign_id);
ALTER TABLE ONLY daily_metrics
    ADD CONSTRAINT daily_metrics_pkey PRIMARY KEY (metric_id);
ALTER TABLE ONLY daily_metrics
    ADD CONSTRAINT daily_metrics_upload_id_metric_date_key UNIQUE (upload_id, metric_date);
ALTER TABLE ONLY export_history
    ADD CONSTRAINT export_history_pkey PRIMARY KEY (log_id);
ALTER TABLE ONLY forecast_params
    ADD CONSTRAINT forecast_params_forecast_id_key UNIQUE (forecast_id);
ALTER TABLE ONLY forecast_params
    ADD CONSTRAINT forecast_params_pkey PRIMARY KEY (param_id);
ALTER TABLE ONLY forecast_results
    ADD CONSTRAINT forecast_results_forecast_id_key UNIQUE (forecast_id);
ALTER TABLE ONLY forecast_results
    ADD CONSTRAINT forecast_results_pkey PRIMARY KEY (result_id);
ALTER TABLE ONLY forecasts
    ADD CONSTRAINT forecasts_pkey PRIMARY KEY (forecast_id);
ALTER TABLE ONLY ml_models
    ADD CONSTRAINT ml_models_model_name_model_version_key UNIQUE (model_name, model_version);
ALTER TABLE ONLY ml_models
    ADD CONSTRAINT ml_models_pkey PRIMARY KEY (model_id);
ALTER TABLE ONLY organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (organization_id);
ALTER TABLE ONLY permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (permission_id);
ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (role_id);
ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_role_name_key UNIQUE (role_name);
ALTER TABLE ONLY uploads
    ADD CONSTRAINT uploads_pkey PRIMARY KEY (upload_id);
ALTER TABLE ONLY users
    ADD CONSTRAINT users_email_key UNIQUE (email);
ALTER TABLE ONLY users
    ADD CONSTRAINT users_login_key UNIQUE (login);
ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);
CREATE INDEX idx_ad_accounts_organization ON ad_accounts USING btree (organization_id);
CREATE INDEX idx_audit_actions_time ON audit_actions USING btree (action_time);
CREATE INDEX idx_audit_actions_user ON audit_actions USING btree (user_id);
CREATE INDEX idx_campaign_access_user ON campaign_access USING btree (user_id);
CREATE INDEX idx_campaigns_account ON campaigns USING btree (account_id);
CREATE INDEX idx_campaigns_organization ON campaigns USING btree (organization_id);
CREATE INDEX idx_campaigns_status ON campaigns USING btree (status);
CREATE INDEX idx_daily_metrics_date ON daily_metrics USING btree (metric_date);
CREATE INDEX idx_daily_metrics_upload_date ON daily_metrics USING btree (upload_id, metric_date);
CREATE INDEX idx_export_history_forecast ON export_history USING btree (forecast_id);
CREATE INDEX idx_forecasts_campaign ON forecasts USING btree (campaign_id);
CREATE INDEX idx_forecasts_model ON forecasts USING btree (model_id);
CREATE INDEX idx_forecasts_status ON forecasts USING btree (status);
CREATE INDEX idx_ml_models_organization ON ml_models USING btree (organization_id);
CREATE INDEX idx_permissions_role ON permissions USING btree (role_id);
CREATE INDEX idx_uploads_campaign ON uploads USING btree (campaign_id);
CREATE INDEX idx_users_organization ON users USING btree (organization_id);
CREATE INDEX idx_users_role ON users USING btree (role_id);
ALTER TABLE ONLY ad_accounts
    ADD CONSTRAINT ad_accounts_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(organization_id);
ALTER TABLE ONLY audit_actions
    ADD CONSTRAINT audit_actions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id);
ALTER TABLE ONLY campaign_access
    ADD CONSTRAINT campaign_access_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id);
ALTER TABLE ONLY campaign_access
    ADD CONSTRAINT campaign_access_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id);
ALTER TABLE ONLY campaigns
    ADD CONSTRAINT campaigns_account_id_fkey FOREIGN KEY (account_id) REFERENCES ad_accounts(account_id);
ALTER TABLE ONLY campaigns
    ADD CONSTRAINT campaigns_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(organization_id);
ALTER TABLE ONLY daily_metrics
    ADD CONSTRAINT daily_metrics_upload_id_fkey FOREIGN KEY (upload_id) REFERENCES uploads(upload_id);
ALTER TABLE ONLY export_history
    ADD CONSTRAINT export_history_forecast_id_fkey FOREIGN KEY (forecast_id) REFERENCES forecasts(forecast_id);
ALTER TABLE ONLY forecast_params
    ADD CONSTRAINT forecast_params_forecast_id_fkey FOREIGN KEY (forecast_id) REFERENCES forecasts(forecast_id);
ALTER TABLE ONLY forecast_results
    ADD CONSTRAINT forecast_results_forecast_id_fkey FOREIGN KEY (forecast_id) REFERENCES forecasts(forecast_id);
ALTER TABLE ONLY forecast_results
    ADD CONSTRAINT forecast_results_own_model_id_fkey FOREIGN KEY (own_model_id) REFERENCES ml_models(model_id);
ALTER TABLE ONLY forecasts
    ADD CONSTRAINT forecasts_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id);
ALTER TABLE ONLY forecasts
    ADD CONSTRAINT forecasts_model_id_fkey FOREIGN KEY (model_id) REFERENCES ml_models(model_id);
ALTER TABLE ONLY ml_models
    ADD CONSTRAINT ml_models_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(organization_id);
ALTER TABLE ONLY permissions
    ADD CONSTRAINT permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(role_id);
ALTER TABLE ONLY uploads
    ADD CONSTRAINT uploads_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id);
ALTER TABLE ONLY users
    ADD CONSTRAINT users_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES organizations(organization_id);
ALTER TABLE ONLY users
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(role_id);
