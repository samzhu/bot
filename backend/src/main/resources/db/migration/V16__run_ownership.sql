CREATE TABLE run_ownership (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES chat_run(id) ON DELETE CASCADE,
    owner_token VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL
);

COMMENT ON TABLE run_ownership IS 'Run 執行權的持久租約；沒有此紀錄的既有 Run 不可推定失聯。';
COMMENT ON COLUMN run_ownership.owner_token IS '不可重用的執行權憑證；接管時更換，以阻擋舊執行者寫入。';
COMMENT ON COLUMN run_ownership.generation IS '接管時遞增的世代，配合 token 區分各次執行權。';
COMMENT ON COLUMN run_ownership.lease_until IS '以 PostgreSQL 時間判定的租約期限，過期只允許競爭接管，不代表程序已停止。';

ALTER TABLE workspace_admission_registration ADD COLUMN start_lease_until TIMESTAMP WITH TIME ZONE;
COMMENT ON COLUMN workspace_admission_registration.start_lease_until IS '建立 Run 前的持久起始租約；NULL 表示舊資料無可信執行證據。';
