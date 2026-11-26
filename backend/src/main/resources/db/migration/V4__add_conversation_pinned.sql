-- Migration: V4__add_conversation_pinned.sql
-- Issue #11: Add pinned column and composite index for conversation pinning

ALTER TABLE conversation ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN conversation.pinned IS '對話釘選狀態，true 表示已釘選至側欄頂部，false 為預設未釘選。';

CREATE INDEX IF NOT EXISTS idx_conv_user_pinned_updated
    ON conversation (user_id, pinned, updated_at DESC, id DESC);
