-- Table: schema_version
CREATE TABLE IF NOT EXISTS schema_version (
    script_name VARCHAR(255) PRIMARY KEY,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE schema_version IS '技術啟動模組 migration 執行紀錄表，記錄已執行的 SQL 檔名與時間戳記。';
COMMENT ON COLUMN schema_version.script_name IS 'SQL 遷移檔名（主鍵），例如 V1__initial_schema.sql。';
COMMENT ON COLUMN schema_version.executed_at IS '該遷移腳本於本機資料庫執行的完成時間戳記。';

-- Table: bot
CREATE TABLE IF NOT EXISTS bot (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    system_prompt TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE bot IS '助理人設設定表，儲存供使用者選擇之助理名稱與 System Prompt 指示。';
COMMENT ON COLUMN bot.id IS '助理識別碼（主鍵），例如 bot-general。';
COMMENT ON COLUMN bot.name IS '助理顯示名稱，供 UI 頂欄與下拉選單呈現。';
COMMENT ON COLUMN bot.system_prompt IS '送給模型之核心人設指令與行為約束模板。';
COMMENT ON COLUMN bot.created_at IS '助理建立時間戳記。';

-- Table: conversation
CREATE TABLE IF NOT EXISTS conversation (
    id VARCHAR(64) PRIMARY KEY,
    bot_id VARCHAR(64) NOT NULL REFERENCES bot(id),
    title VARCHAR(255) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conv_user_updated ON conversation (user_id, updated_at DESC, id DESC);

COMMENT ON TABLE conversation IS '對話主題表，記錄使用者與特定 Bot 建立之持續性聊天歷程。';
COMMENT ON COLUMN conversation.id IS '對話唯一識別碼（字串ID，正式流程用UUID/local範例固定ID）。';
COMMENT ON COLUMN conversation.bot_id IS '所屬助理外鍵，參照 bot(id)。';
COMMENT ON COLUMN conversation.title IS '對話標題，由建立對話時指定或預設為 New Conversation。';
COMMENT ON COLUMN conversation.user_id IS '擁有該對話之使用者識別碼（擁有者，local固定dev-user）。';
COMMENT ON COLUMN conversation.created_at IS '對話建立時間戳記。';
COMMENT ON COLUMN conversation.updated_at IS '對話最後發言或更新時間戳記，用於最近對話排序。';

-- Table: chat_run (created before message to allow FK from message.run_id)
CREATE TABLE IF NOT EXISTS chat_run (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    client_request_id VARCHAR(128) NOT NULL,
    input_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_run_conv_id UNIQUE (conversation_id, id)
);

-- Constraint: single active run per conversation
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_run_per_conv
ON chat_run (conversation_id)
WHERE status IN ('PENDING', 'RUNNING');

-- Constraint: deduplication per conversation and client_request_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_client_request_per_conv
ON chat_run (conversation_id, client_request_id);

CREATE INDEX IF NOT EXISTS idx_run_conv_created ON chat_run (conversation_id, created_at DESC, id DESC);

COMMENT ON TABLE chat_run IS '生成執行歷程表，記錄單次模型生成請求之生命週期、狀態與取消中斷。';
COMMENT ON COLUMN chat_run.id IS '執行唯一識別碼（字串ID，正式流程用UUID/local範例固定ID），對齊 SSE runId。';
COMMENT ON COLUMN chat_run.conversation_id IS '所屬對話外鍵，參照 conversation(id)。';
COMMENT ON COLUMN chat_run.client_request_id IS '前端發起請求之冪等防重識別碼。';
COMMENT ON COLUMN chat_run.input_text IS '該輪次使用者原始輸入字串。';
COMMENT ON COLUMN chat_run.status IS '執行狀態：PENDING, RUNNING, COMPLETED, FAILED, CANCELLED。';
COMMENT ON COLUMN chat_run.error_message IS '執行失敗或異常時記錄之錯誤摘要訊息。';
COMMENT ON COLUMN chat_run.created_at IS '執行發起時間戳記。';
COMMENT ON COLUMN chat_run.updated_at IS '執行狀態推進或終態達成時間戳記。';

-- Table: message (references conversation and chat_run with composite integrity)
CREATE TABLE IF NOT EXISTS message (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content TEXT NOT NULL,
    interrupted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_run_role UNIQUE (run_id, role),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_msg_conv_run FOREIGN KEY (conversation_id, run_id) REFERENCES chat_run(conversation_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_msg_conv_created ON message (conversation_id, created_at ASC, id ASC);
CREATE INDEX IF NOT EXISTS idx_msg_run_id ON message (run_id);

COMMENT ON TABLE message IS '訊息歷程表，記錄對話中所有使用者與助手之權威文字發言與中斷標記。';
COMMENT ON COLUMN message.id IS '訊息唯一識別碼（字串ID，正式流程用UUID/local範例固定ID）。';
COMMENT ON COLUMN message.conversation_id IS '所屬對話外鍵，參照 conversation(id)。';
COMMENT ON COLUMN message.run_id IS '產生該訊息之執行外鍵，參照 chat_run(id)。';
COMMENT ON COLUMN message.role IS '訊息發言角色：USER, ASSISTANT, SYSTEM。';
COMMENT ON COLUMN message.content IS '原始文字或 Markdown 回應字串。';
COMMENT ON COLUMN message.interrupted IS '標記助手訊息是否在生成中途被使用者手動 Stop 或異常中斷。';
COMMENT ON COLUMN message.created_at IS '訊息持久化建立時間戳記。';
