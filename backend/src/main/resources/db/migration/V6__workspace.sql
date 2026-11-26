CREATE TABLE IF NOT EXISTS workspace (
 id VARCHAR(64) PRIMARY KEY,
 name VARCHAR(255) NOT NULL,
 personal_owner_id VARCHAR(64) UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS workspace_member (
 workspace_id VARCHAR(64) NOT NULL REFERENCES workspace(id),
 user_id VARCHAR(64) NOT NULL,
 role VARCHAR(16) NOT NULL CHECK (role IN ('MANAGER', 'MEMBER')),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_workspace_member_user ON workspace_member(user_id, active, workspace_id);
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);
INSERT INTO workspace(id, name, personal_owner_id)
SELECT 'personal-' || md5(user_id), 'Personal Workspace', user_id FROM conversation GROUP BY user_id
ON CONFLICT (personal_owner_id) DO NOTHING;
INSERT INTO workspace_member(workspace_id, user_id, role)
SELECT w.id, w.personal_owner_id, 'MANAGER' FROM workspace w WHERE w.personal_owner_id IS NOT NULL
ON CONFLICT (workspace_id, user_id) DO NOTHING;
UPDATE conversation c SET workspace_id = w.id FROM workspace w
WHERE c.workspace_id IS NULL AND w.personal_owner_id = c.user_id;
ALTER TABLE conversation ALTER COLUMN workspace_id SET NOT NULL;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_conversation_workspace' AND conrelid = 'conversation'::regclass) THEN
  ALTER TABLE conversation ADD CONSTRAINT fk_conversation_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id);
 END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_conversation_workspace_owner ON conversation(workspace_id, user_id, pinned, updated_at DESC, id DESC);
COMMENT ON TABLE workspace IS '工作區，作為對話及其使用量的歸屬範圍。';
COMMENT ON COLUMN workspace.personal_owner_id IS '個人工作區的擁有者；共用工作區為 NULL。';
COMMENT ON TABLE workspace_member IS '使用者在工作區的成員關係與角色。';
COMMENT ON COLUMN workspace_member.role IS '工作區角色：MANAGER 或 MEMBER。';
COMMENT ON COLUMN workspace_member.active IS '是否目前可存取工作區；停用保留歷史歸屬。';
COMMENT ON COLUMN conversation.workspace_id IS '對話建立時的工作區歸屬，不因成員權限變更而改變。';
COMMENT ON COLUMN workspace.id IS '工作區識別碼。';
COMMENT ON COLUMN workspace.name IS '工作區顯示名稱。';
COMMENT ON COLUMN workspace.created_at IS '工作區建立時間。';
COMMENT ON COLUMN workspace_member.workspace_id IS '成員所屬工作區。';
COMMENT ON COLUMN workspace_member.user_id IS '成員的可信使用者識別碼。';
COMMENT ON COLUMN workspace_member.created_at IS '成員關係建立時間。';
COMMENT ON COLUMN conversation.user_id IS '對話建立者的使用者識別碼；成員權限變更不更改歷史擁有者。';
