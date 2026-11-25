ALTER TABLE conversation_title_work ADD COLUMN next_attempt_at TIMESTAMPTZ;
COMMENT ON COLUMN conversation_title_work.next_attempt_at IS '已知未派送的暫緩重試時間，不延長原始 ready_until。';
ALTER TABLE provider_attempt ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'CHAT';
ALTER TABLE provider_attempt ADD COLUMN title_conversation_id VARCHAR(64);
ALTER TABLE provider_attempt ADD CONSTRAINT provider_attempt_purpose_check CHECK (
    (purpose='CHAT' AND title_conversation_id IS NULL) OR
    (purpose='CONVERSATION_TITLE' AND title_conversation_id IS NOT NULL AND run_id IS NULL)
);
CREATE UNIQUE INDEX provider_attempt_title_work_idx ON provider_attempt(title_conversation_id) WHERE purpose='CONVERSATION_TITLE';
COMMENT ON COLUMN provider_attempt.purpose IS 'CHAT 聊天呼叫或 CONVERSATION_TITLE 背景命名。';
COMMENT ON COLUMN provider_attempt.title_conversation_id IS '命名工作識別，保留於對話刪除後供用量追溯。';
CREATE TABLE workspace_title_reservation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL REFERENCES workspace(id),
    actor_id VARCHAR(64) NOT NULL,
    partition VARCHAR(16) NOT NULL CHECK (partition IN ('REAL','SIMULATION')),
    reserved_at TIMESTAMPTZ NOT NULL,
    month_start DATE NOT NULL,
    amount_usd NUMERIC(30,12) NOT NULL CHECK (amount_usd >= 0),
    catalog_revision_id VARCHAR(64) NOT NULL REFERENCES pricing_catalog_revision(id),
    attempt_id VARCHAR(64) UNIQUE REFERENCES provider_attempt(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('HELD','UNKNOWN','SETTLED'))
);
CREATE INDEX workspace_title_reservation_month_idx ON workspace_title_reservation(workspace_id,partition,month_start) WHERE state IN ('HELD','UNKNOWN');
COMMENT ON TABLE workspace_title_reservation IS '標題模型派送前在工作區預算下保留的最大費用；未知結果保留以防重複支出。';
COMMENT ON COLUMN workspace_title_reservation.conversation_id IS '持久命名工作識別，不依賴聊天 Run。';
COMMENT ON COLUMN workspace_title_reservation.amount_usd IS '以固定目錄和模型最大輸入及輸出上界計算的 USD 預留。';
COMMENT ON COLUMN workspace_title_reservation.state IS 'HELD 尚未結算、UNKNOWN 結果不明、SETTLED 已結算。';
