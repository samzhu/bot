CREATE TABLE member_weekly_budget (
 workspace_id VARCHAR(64) NOT NULL, member_user_id VARCHAR(64) NOT NULL,
 partition VARCHAR(16) NOT NULL CHECK (partition IN ('REAL','SIMULATION')),
 enabled BOOLEAN NOT NULL DEFAULT FALSE, cap_usd NUMERIC(30,12),
 config_version BIGINT NOT NULL DEFAULT 0 CHECK (config_version >= 0),
 PRIMARY KEY(workspace_id,member_user_id,partition),
 FOREIGN KEY(workspace_id,member_user_id) REFERENCES workspace_member(workspace_id,user_id),
 CHECK(cap_usd IS NULL OR cap_usd >= 0), CHECK(NOT enabled OR cap_usd IS NOT NULL)
);
COMMENT ON TABLE member_weekly_budget IS '成員跨週持續有效的週額度設定，真實與模擬分帳。';
COMMENT ON COLUMN member_weekly_budget.member_user_id IS '可信工作區成員 user_id，不是獨立成員列識別碼。';
COMMENT ON COLUMN member_weekly_budget.cap_usd IS 'USD 週上限，啟用時不可為 NULL。';
COMMENT ON COLUMN member_weekly_budget.config_version IS '設定 CAS 版本，不因週期變動而改變。';
CREATE TABLE member_weekly_budget_command (
 workspace_id VARCHAR(64) NOT NULL, member_user_id VARCHAR(64) NOT NULL, partition VARCHAR(16) NOT NULL,
 actor_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
 expected_config_version BIGINT NOT NULL, enabled BOOLEAN NOT NULL, cap_usd NUMERIC(30,12),
 applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(workspace_id,member_user_id,partition,actor_id,idempotency_key),
 FOREIGN KEY(workspace_id,member_user_id,partition) REFERENCES member_weekly_budget(workspace_id,member_user_id,partition)
);
COMMENT ON TABLE member_weekly_budget_command IS '可信設定者與分帳範圍內持久去重之週額度命令。';
ALTER TABLE workspace_admission_finalization DROP CONSTRAINT workspace_admission_finalization_terminal_reason_check;
ALTER TABLE workspace_admission_finalization ADD CONSTRAINT workspace_admission_finalization_terminal_reason_check
 CHECK (terminal_reason IN ('CAPTURE_FAILED','ADMISSION_RECOVERY','PROMPT_CONTEXT_UNAVAILABLE','WORKSPACE_ACCESS_DENIED','RUN_TERMINATED_BEFORE_DISPATCH','PROVIDER_STREAM_ERROR','BUDGET_CANCELLATION_SUPERSEDED','WEEK_CAP','WEEK_UNKNOWN'));
ALTER TABLE workspace_admission_finalization ADD CONSTRAINT weekly_denial_not_dispatched_check
 CHECK (terminal_reason NOT IN ('WEEK_CAP','WEEK_UNKNOWN') OR terminal_kind='NOT_DISPATCHED');
