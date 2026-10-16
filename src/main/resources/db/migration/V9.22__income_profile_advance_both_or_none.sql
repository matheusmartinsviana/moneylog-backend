ALTER TABLE income_profiles DROP CONSTRAINT ck_income_profiles_advance;

ALTER TABLE income_profiles ADD CONSTRAINT ck_income_profiles_advance CHECK (
    (advance_day IS NULL AND advance_bps IS NULL)
    OR (advance_day IS NOT NULL AND advance_bps IS NOT NULL
        AND advance_day BETWEEN 1 AND 28 AND advance_bps BETWEEN 0 AND 10000));
