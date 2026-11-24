-- Minimal standalone synthetic demonstration data script for local development simulation.
-- Strictly scoped: one representative Conversation with Message history and synthetic/simulation proof.
-- Uses dedicated synthetic workspace ID to never attach to or pollute existing non-fixture personal workspaces.
-- Framework-owned Session/SessionEvent tables are strictly excluded (left to framework/SessionService).
-- Default bot and schema preparation are owned by release provisioning.
-- Safe, deterministic, idempotent, non-destructive, strictly preserves existing data.
-- Enclosed in a single transaction with pre-flight positive payload-proving fail-closed checks.

BEGIN;

DO $$
DECLARE
  t_conv REGCLASS;
  t_bot REGCLASS;
  t_run REGCLASS;
  t_msg REGCLASS;
  t_ws REGCLASS;
  t_wsm REGCLASS;
  t_att REGCLASS;
  t_snap REGCLASS;
  existing_ws_id VARCHAR;
  existing_ws_name VARCHAR;
  existing_conv_bot VARCHAR;
  existing_conv_ws VARCHAR;
  existing_conv_user VARCHAR;
  existing_run_status VARCHAR;
  existing_run_conv VARCHAR;
  existing_att_sim BOOLEAN;
  existing_att_provider VARCHAR;
  existing_att_ws VARCHAR;
  existing_snap_att VARCHAR;
BEGIN
  -- 1. Positive isolation: verify required application schema tables exist before executing any SQL mutations
  SELECT to_regclass('conversation') INTO t_conv;
  SELECT to_regclass('bot') INTO t_bot;
  SELECT to_regclass('chat_run') INTO t_run;
  SELECT to_regclass('message') INTO t_msg;
  SELECT to_regclass('workspace') INTO t_ws;
  SELECT to_regclass('workspace_member') INTO t_wsm;
  SELECT to_regclass('provider_attempt') INTO t_att;
  SELECT to_regclass('usage_snapshot') INTO t_snap;

  IF t_conv IS NULL OR t_bot IS NULL OR t_run IS NULL OR t_msg IS NULL
     OR t_ws IS NULL OR t_wsm IS NULL OR t_att IS NULL OR t_snap IS NULL THEN
    RAISE EXCEPTION 'Required application schema tables missing. Schema migration must precede synthetic demo seeding.';
  END IF;

  -- 2. Positive check: default bot preparation is owned by release provisioning
  IF NOT EXISTS (SELECT 1 FROM bot WHERE id = 'bot-general') THEN
    RAISE EXCEPTION 'Required default bot "bot-general" missing. Release provisioning must precede synthetic demo seeding.';
  END IF;

  -- 3. Collision guard: Workspace isolation
  -- Reject if dev-user already has a different non-fixture personal workspace
  IF EXISTS (SELECT 1 FROM workspace WHERE personal_owner_id = 'dev-user') THEN
    SELECT id, name INTO existing_ws_id, existing_ws_name FROM workspace WHERE personal_owner_id = 'dev-user';
    IF existing_ws_id IS DISTINCT FROM 'seed-workspace-demo-01' THEN
      RAISE EXCEPTION 'Foreign personal workspace already exists for dev-user (id: %, name: %). Cannot attach fixture.', existing_ws_id, existing_ws_name;
    END IF;
  END IF;

  -- If seed workspace already exists, prove exact synthetic payload
  IF EXISTS (SELECT 1 FROM workspace WHERE id = 'seed-workspace-demo-01') THEN
    SELECT personal_owner_id, name INTO existing_ws_id, existing_ws_name FROM workspace WHERE id = 'seed-workspace-demo-01';
    IF existing_ws_id IS DISTINCT FROM 'dev-user' OR existing_ws_name IS DISTINCT FROM 'Synthetic Demonstration Workspace' THEN
      RAISE EXCEPTION 'Synthetic payload mismatch on workspace seed-workspace-demo-01 (owner: %, name: %)', existing_ws_id, existing_ws_name;
    END IF;
  END IF;

  -- 4. Collision guard: Conversation payload verification
  IF EXISTS (SELECT 1 FROM conversation WHERE id = 'seed-conv-completed-01') THEN
    SELECT bot_id, workspace_id, user_id INTO existing_conv_bot, existing_conv_ws, existing_conv_user FROM conversation WHERE id = 'seed-conv-completed-01';
    IF existing_conv_bot IS DISTINCT FROM 'bot-general'
       OR existing_conv_ws IS DISTINCT FROM 'seed-workspace-demo-01'
       OR existing_conv_user IS DISTINCT FROM 'dev-user' THEN
      RAISE EXCEPTION 'Synthetic payload collision on conversation seed-conv-completed-01 (bot: %, ws: %, user: %)', existing_conv_bot, existing_conv_ws, existing_conv_user;
    END IF;
  END IF;

  -- 5. Collision guard: Chat run payload verification
  IF EXISTS (SELECT 1 FROM chat_run WHERE id = 'seed-run-completed-01') THEN
    SELECT conversation_id, status INTO existing_run_conv, existing_run_status FROM chat_run WHERE id = 'seed-run-completed-01';
    IF existing_run_conv IS DISTINCT FROM 'seed-conv-completed-01' OR existing_run_status IS DISTINCT FROM 'COMPLETED' THEN
      RAISE EXCEPTION 'Synthetic payload collision on chat_run seed-run-completed-01 (conv: %, status: %)', existing_run_conv, existing_run_status;
    END IF;
  END IF;

  -- 6. Collision guard: Provider attempt payload verification
  IF EXISTS (SELECT 1 FROM provider_attempt WHERE id = 'seed-usage-attempt-01') THEN
    SELECT simulation, provider, workspace_id INTO existing_att_sim, existing_att_provider, existing_att_ws FROM provider_attempt WHERE id = 'seed-usage-attempt-01';
    IF existing_att_sim IS NOT TRUE
       OR existing_att_provider IS DISTINCT FROM 'FAKE'
       OR existing_att_ws IS DISTINCT FROM 'seed-workspace-demo-01' THEN
      RAISE EXCEPTION 'Synthetic payload collision on provider_attempt seed-usage-attempt-01 (sim: %, provider: %, ws: %)', existing_att_sim, existing_att_provider, existing_att_ws;
    END IF;
  END IF;

  -- 7. Collision guard: Usage snapshot payload verification
  IF EXISTS (SELECT 1 FROM usage_snapshot WHERE id = 'seed-usage-snapshot-01') THEN
    SELECT attempt_id INTO existing_snap_att FROM usage_snapshot WHERE id = 'seed-usage-snapshot-01';
    IF existing_snap_att IS DISTINCT FROM 'seed-usage-attempt-01' THEN
      RAISE EXCEPTION 'Synthetic payload collision on usage_snapshot seed-usage-snapshot-01 (attempt: %)', existing_snap_att;
    END IF;
  END IF;
END $$;

-- 1. Dedicated synthetic demonstration workspace and membership
INSERT INTO workspace (id, name, personal_owner_id, created_at)
VALUES ('seed-workspace-demo-01', 'Synthetic Demonstration Workspace', 'dev-user', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_member (workspace_id, user_id, role, active, created_at)
VALUES ('seed-workspace-demo-01', 'dev-user', 'MANAGER', TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (workspace_id, user_id) DO NOTHING;

-- 2. Exactly one representative Synthetic Conversation, Run, and Messages (COMPLETED)
-- Associated with the release-provisioned 'bot-general' and isolated synthetic workspace
INSERT INTO conversation (id, bot_id, title, user_id, workspace_id, pinned, created_at, updated_at)
VALUES (
    'seed-conv-completed-01',
    'bot-general',
    '示範：成功問答 (COMPLETED)',
    'dev-user',
    'seed-workspace-demo-01',
    TRUE,
    CURRENT_TIMESTAMP - interval '300 second',
    CURRENT_TIMESTAMP - interval '298 second'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO chat_run (id, conversation_id, client_request_id, input_text, status, error_message, execution_mode, created_at, updated_at)
VALUES (
    'seed-run-completed-01',
    'seed-conv-completed-01',
    'seed-req-completed-01',
    '請用一句話介紹什麼是 Spring AI？',
    'COMPLETED',
    NULL,
    'LOCAL',
    CURRENT_TIMESTAMP - interval '300 second',
    CURRENT_TIMESTAMP - interval '298 second'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO message (id, conversation_id, run_id, role, content, interrupted, created_at)
VALUES
    (
        'seed-msg-completed-user-01',
        'seed-conv-completed-01',
        'seed-run-completed-01',
        'USER',
        '請用一句話介紹什麼是 Spring AI？',
        FALSE,
        CURRENT_TIMESTAMP - interval '300 second'
    ),
    (
        'seed-msg-completed-assistant-01',
        'seed-conv-completed-01',
        'seed-run-completed-01',
        'ASSISTANT',
        E'Spring AI 是一個旨在簡化 Java 開發者整合人工智慧功能的應用框架：\n\n* **核心抽象**：提供 `ChatClient`、`Prompt` 與多模型切換介面。\n* **對話記憶**：官方支援 `SessionService` 與短期對話歷史串接。\n\n```java\nChatResponse response = chatClient.prompt()\n    .user("Hello World")\n    .call()\n    .chatResponse();\n```',
        FALSE,
        CURRENT_TIMESTAMP - interval '298 second'
    )
ON CONFLICT (id) DO NOTHING;

-- 3. Synthetic Demonstration Usage Ledger (simulation proof)
-- Step 3.1: Insert provider_attempt with projection_snapshot_id = NULL
INSERT INTO provider_attempt (
    id,
    run_id,
    workspace_id,
    actor_id,
    provider,
    endpoint,
    model,
    channel,
    adapter_version,
    simulation,
    attempt_coverage,
    prepared_at,
    started_at,
    finished_at,
    execution_outcome,
    usage_presence,
    capture_completeness,
    projection_state
)
VALUES (
    'seed-usage-attempt-01',
    'seed-run-completed-01',
    'seed-workspace-demo-01',
    'dev-user',
    'FAKE',
    'local-demo',
    'synthetic-demo',
    'FAKE',
    'seed-1',
    TRUE,
    'VERIFIED',
    CURRENT_TIMESTAMP - interval '300 second',
    CURRENT_TIMESTAMP - interval '300 second',
    CURRENT_TIMESTAMP - interval '298 second',
    'SUCCEEDED',
    'PRESENT',
    'COMPLETE',
    'MISSING'
)
ON CONFLICT (id) DO NOTHING;

-- Step 3.2: Insert usage_snapshot referencing provider_attempt
INSERT INTO usage_snapshot (
    id,
    attempt_id,
    event_id,
    payload_hash,
    ordinal,
    semantic,
    source_layer,
    schema_version,
    adapter_version,
    capture_version,
    capture_completeness,
    raw_state,
    raw_usage,
    captured_at
)
VALUES (
    'seed-usage-snapshot-01',
    'seed-usage-attempt-01',
    'seed-event-01',
    'cd98319cf41c7a65a21da0dab338237c63966f33d63bbacc30ffbff31ffef929',
    1,
    'FINAL',
    'FAKE_RAW',
    'seed-1',
    'seed-1',
    'seed-1',
    'COMPLETE',
    'JSONB',
    '{"input":12,"output":4,"cacheRead":0,"example":{"kind":"synthetic","note":"not tokenizer counts"}}'::jsonb,
    CURRENT_TIMESTAMP - interval '300 second'
)
ON CONFLICT (id) DO NOTHING;

-- Step 3.3: Update provider_attempt with projection snapshot
UPDATE provider_attempt
SET projection_snapshot_id = 'seed-usage-snapshot-01',
    projection_state = 'VALID',
    projection_version = 1
WHERE id = 'seed-usage-attempt-01'
  AND projection_snapshot_id IS NULL
  AND run_id = 'seed-run-completed-01'
  AND EXISTS (
      SELECT 1 FROM usage_snapshot
      WHERE id = 'seed-usage-snapshot-01' AND attempt_id = 'seed-usage-attempt-01'
  );

COMMIT;
