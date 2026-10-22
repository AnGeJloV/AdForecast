ALTER TABLE users ADD COLUMN full_name VARCHAR(255) NOT NULL DEFAULT '';

UPDATE users SET full_name = 'Администратор' WHERE user_id = 1;
UPDATE users SET full_name = 'Маркетолог' WHERE user_id = 2;

ALTER TABLE campaigns ADD COLUMN in_training BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE campaigns ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ml_models ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE ml_models SET is_active = TRUE
WHERE model_id = (
    SELECT model_id
    FROM ml_models
    ORDER BY trained_at DESC, model_id DESC
    LIMIT 1
);

CREATE TABLE audit_actions (
    log_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users (user_id),
    action VARCHAR(100) NOT NULL,
    object_type VARCHAR(50),
    object_id BIGINT,
    details VARCHAR(1000),
    action_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip VARCHAR(45)
);

CREATE INDEX idx_audit_actions_time ON audit_actions (action_time);
CREATE INDEX idx_audit_actions_user ON audit_actions (user_id);
