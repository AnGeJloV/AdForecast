-- Выбор области прогнозирования («своя + общая», только своя, только общая)
-- и контроль разумности бюджета: границы дневного бюджета обучающих данных.

ALTER TABLE forecast_results ADD COLUMN model_scope VARCHAR(10) NOT NULL DEFAULT 'BLEND';
ALTER TABLE forecast_results ADD COLUMN day_budget_min NUMERIC(12, 2);
ALTER TABLE forecast_results ADD COLUMN day_budget_max NUMERIC(12, 2);
ALTER TABLE forecast_results ADD COLUMN data_note VARCHAR(255);
