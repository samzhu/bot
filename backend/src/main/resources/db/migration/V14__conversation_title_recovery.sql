ALTER TABLE conversation_title_work DROP CONSTRAINT IF EXISTS conversation_title_work_state_check;
ALTER TABLE conversation_title_work ADD CONSTRAINT conversation_title_work_state_check
    CHECK (state IN ('WAITING', 'READY', 'CLAIMED', 'DONE', 'FAILED'));
ALTER TABLE conversation_title_work ADD COLUMN IF NOT EXISTS ready_until TIMESTAMPTZ;
ALTER TABLE conversation_title_work ADD COLUMN IF NOT EXISTS claimed_until TIMESTAMPTZ;
-- Older claims have no reliable dispatch or deadline evidence; never replay them.
UPDATE conversation_title_work SET state='FAILED' WHERE state='CLAIMED' AND claimed_until IS NULL;
COMMENT ON COLUMN conversation_title_work.state IS 'WAITING 尚未可派送、READY 已可派送、CLAIMED 結果可能不明、DONE 已提交、FAILED 已終止。';
COMMENT ON COLUMN conversation_title_work.ready_until IS '已知未派送工作可領取的期限。';
COMMENT ON COLUMN conversation_title_work.claimed_until IS '已領取工作結果不明時終止等待的期限，不重新派送。';

CREATE INDEX IF NOT EXISTS idx_title_work_ready ON conversation_title_work (ready_until) WHERE state='READY';
CREATE INDEX IF NOT EXISTS idx_title_work_claimed ON conversation_title_work (claimed_until) WHERE state='CLAIMED';
