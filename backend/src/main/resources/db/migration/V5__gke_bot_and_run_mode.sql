-- Migration V5: Add GKE Bot definition fields, ownership constraints, and chat_run execution mode provenance

-- Alter bot table with backfilled updated_at and GKE owner constraint
ALTER TABLE bot
    ADD COLUMN IF NOT EXISTS target VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS instructions TEXT,
    ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

-- Backfill existing bot updated_at from created_at before enforcing NOT NULL
UPDATE bot SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE bot
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE bot
    DROP CONSTRAINT IF EXISTS chk_bot_target;
ALTER TABLE bot
    ADD CONSTRAINT chk_bot_target CHECK (target IN ('LOCAL', 'GKE_REMOTE'));

ALTER TABLE bot
    DROP CONSTRAINT IF EXISTS chk_bot_gke_owner;
ALTER TABLE bot
    ADD CONSTRAINT chk_bot_gke_owner CHECK (target != 'GKE_REMOTE' OR owner_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_bot_owner ON bot (owner_id, updated_at DESC, id DESC);

COMMENT ON TABLE bot IS '助理定義表，儲存本機助理 (LOCAL) 與 GKE 自主代理 (GKE_REMOTE) 之規格定義與明示模擬狀態。';
COMMENT ON COLUMN bot.target IS '助理目標架構：LOCAL（本機端助理）或 GKE_REMOTE（遠端 GKE 自主代理）。';
COMMENT ON COLUMN bot.description IS '助理用途說明，供卡片清單與詳情檢視。';
COMMENT ON COLUMN bot.system_prompt IS '本機助理 (LOCAL) 核心人設指令與行為約束模板；GKE 代理不使用此欄位。';
COMMENT ON COLUMN bot.instructions IS 'GKE 代理 (GKE_REMOTE) 之待部署工作指示，於明示模擬模式下作為本機推論 System Instruction；非即時正式提示詞。';
COMMENT ON COLUMN bot.owner_id IS '助理擁有者使用者識別碼（LOCAL 共用為 NULL，GKE 為建立者）。';
COMMENT ON COLUMN bot.updated_at IS '助理最後修改時間戳記，歷史既有資料以 created_at 回填。';

-- Alter chat_run table with strictly runnable execution_mode
ALTER TABLE chat_run
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE chat_run
    DROP CONSTRAINT IF EXISTS chk_chat_run_execution_mode;
ALTER TABLE chat_run
    ADD CONSTRAINT chk_chat_run_execution_mode CHECK (execution_mode IN ('LOCAL', 'SPRING_AI_SIMULATION'));

COMMENT ON COLUMN chat_run.execution_mode IS '執行實際模式來源：LOCAL 或 SPRING_AI_SIMULATION，供審計歷程不因後續配置變更而竄改。';
