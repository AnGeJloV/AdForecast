-- Окно окупаемого бюджета: «нет окна» и «окупается при любом бюджете» — разные ответы,
-- раньше оба выглядели как пустые границы.

ALTER TABLE forecast_results ADD COLUMN budget_any BOOLEAN NOT NULL DEFAULT FALSE;
