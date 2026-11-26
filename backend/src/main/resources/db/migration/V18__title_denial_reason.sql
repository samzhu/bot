ALTER TABLE conversation_title_work ADD COLUMN denial_reason VARCHAR(32)
    CHECK (denial_reason IS NULL OR denial_reason IN ('MONTH_CAP','WEEK_CAP','UNRESOLVED_SPENDING','RATE_UNAVAILABLE'));
COMMENT ON COLUMN conversation_title_work.denial_reason IS '命名派送拒絕原因；舊資料與非拒絕工作為 NULL。';
