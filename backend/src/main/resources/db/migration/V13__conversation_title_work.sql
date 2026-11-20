CREATE TABLE IF NOT EXISTS conversation_title_work (
    conversation_id VARCHAR(64) PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('WAITING', 'CLAIMED', 'DONE', 'FAILED')),
    version BIGINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE conversation_title_work IS '新對話首次回覆後的命名工作及標題版本。';
COMMENT ON COLUMN conversation_title_work.conversation_id IS '所屬對話與命名冪等識別。';
COMMENT ON COLUMN conversation_title_work.state IS 'WAITING 待首段回覆、CLAIMED 已領取、DONE 已提交、FAILED 失敗。';
COMMENT ON COLUMN conversation_title_work.version IS '已提交的標題變更版本。';
