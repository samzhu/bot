CREATE TABLE workspace_monthly_budget (
    workspace_id VARCHAR(64) NOT NULL REFERENCES workspace(id),
    partition VARCHAR(16) NOT NULL CHECK (partition IN ('REAL','SIMULATION')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    cap_usd NUMERIC(30,12),
    config_version BIGINT NOT NULL DEFAULT 0 CHECK (config_version >= 0),
    PRIMARY KEY (workspace_id, partition),
    CHECK (cap_usd IS NULL OR cap_usd >= 0),
    CHECK (NOT enabled OR cap_usd IS NOT NULL)
);
COMMENT ON TABLE workspace_monthly_budget IS '跨月持續有效的workspace預算設定，REAL與SIMULATION分開。';
COMMENT ON COLUMN workspace_monthly_budget.cap_usd IS 'USD月支出上限，NUMERIC(30,12)；啟用時不可為NULL。';
COMMENT ON COLUMN workspace_monthly_budget.config_version IS '設定CAS版本，不因月期或支出變動而遞增。';

CREATE TABLE workspace_monthly_budget_period (
    workspace_id VARCHAR(64) NOT NULL,
    partition VARCHAR(16) NOT NULL,
    period_start DATE NOT NULL CHECK (EXTRACT(DAY FROM period_start) = 1),
    known_subtotal_usd NUMERIC(30,12) DEFAULT 0 CHECK (known_subtotal_usd IS NULL OR known_subtotal_usd >= 0),
    terminal_effective_unknown BOOLEAN NOT NULL DEFAULT FALSE,
    gate_generation BIGINT NOT NULL DEFAULT 0 CHECK (gate_generation >= 0),
    PRIMARY KEY (workspace_id, partition, period_start),
    FOREIGN KEY (workspace_id, partition) REFERENCES workspace_monthly_budget(workspace_id, partition)
);
COMMENT ON TABLE workspace_monthly_budget_period IS '台北月期內的可信支出及未知狀態投影。';
COMMENT ON COLUMN workspace_monthly_budget_period.period_start IS 'Asia/Taipei月初日期，期間採半開區間。';
COMMENT ON COLUMN workspace_monthly_budget_period.known_subtotal_usd IS '真空期為0；全部有效費用未知且無可信定價值時為NULL。';
COMMENT ON COLUMN workspace_monthly_budget_period.gate_generation IS '該期有效支出、未知或政策變更的決策版本。';

CREATE TABLE workspace_admission_registration (
    token VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    workspace_id VARCHAR(64) NOT NULL REFERENCES workspace(id),
    actor_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id),
    partition VARCHAR(16) NOT NULL CHECK (partition IN ('REAL','SIMULATION')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('REGISTERED','PUBLISHED','CANCEL_REQUESTED','TERMINAL')),
    user_cancel_requested_at TIMESTAMPTZ,
    pending_epoch BIGINT NOT NULL DEFAULT 0 CHECK (pending_epoch >= 0),
    pending_id VARCHAR(64) NOT NULL,
    pending_period_start DATE NOT NULL,
    pending_period_end DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX workspace_admission_registration_run_idx ON workspace_admission_registration(run_id, status);
COMMENT ON TABLE workspace_admission_registration IS 'Run admission能力與publication/cancel生命週期；多個attempt claim隸屬同一registration。';
COMMENT ON COLUMN workspace_admission_registration.status IS 'REGISTERED、PUBLISHED、CANCEL_REQUESTED或TERMINAL。';
COMMENT ON COLUMN workspace_admission_registration.user_cancel_requested_at IS '經授權的使用者取消請求首次持久時間；不是終態、回執或派送時間。';
COMMENT ON COLUMN workspace_admission_registration.pending_epoch IS 'pending取消fence epoch；月界轉期時遞增，避免舊pending effect命中新期工作。';

CREATE TABLE workspace_admission_claim (
    claim_id VARCHAR(64) PRIMARY KEY,
    registration_token VARCHAR(64) NOT NULL REFERENCES workspace_admission_registration(token),
    attempt_id VARCHAR(64) NOT NULL UNIQUE,
    period_start DATE NOT NULL CHECK (EXTRACT(DAY FROM period_start) = 1),
    period_end DATE NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('CLAIMED','CANCEL_REQUESTED','TERMINAL','UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (period_end > period_start)
);
CREATE INDEX workspace_admission_claim_registration_idx ON workspace_admission_claim(registration_token, status);
COMMENT ON TABLE workspace_admission_claim IS '每個provider attempt的唯一派送claim與不可重用period fence。';
COMMENT ON COLUMN workspace_admission_claim.generation IS '建立claim時的policy generation metadata，不作在途claim失效條件。';

CREATE TABLE workspace_admission_effect_outbox (
    effect_id VARCHAR(64) PRIMARY KEY,
    registration_token VARCHAR(64) NOT NULL REFERENCES workspace_admission_registration(token),
    target_workspace_id VARCHAR(64) NOT NULL,
    target_partition VARCHAR(16) NOT NULL CHECK (target_partition IN ('REAL','SIMULATION')),
    target_run_id VARCHAR(64) NOT NULL,
    claim_id VARCHAR(64) REFERENCES workspace_admission_claim(claim_id),
    target_attempt_id VARCHAR(64),
    pending_id VARCHAR(64),
    pending_epoch BIGINT,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    delivery_status VARCHAR(16) NOT NULL CHECK (delivery_status IN ('PENDING','ACKNOWLEDGED','STALE')),
    delivery_lease_id VARCHAR(64),
    delivery_lease_until TIMESTAMPTZ,
    intent_receipt VARCHAR(64) NOT NULL UNIQUE,
    ack_receipt VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    CHECK ((claim_id IS NOT NULL AND target_attempt_id IS NOT NULL AND pending_id IS NULL AND pending_epoch IS NULL)
        OR (claim_id IS NULL AND target_attempt_id IS NULL AND pending_id IS NOT NULL AND pending_epoch IS NOT NULL)),
    CHECK (period_end > period_start),
    CHECK ((delivery_lease_id IS NULL) = (delivery_lease_until IS NULL)),
    CHECK ((delivery_status = 'PENDING' AND ack_receipt IS NULL AND acknowledged_at IS NULL)
        OR (delivery_status IN ('ACKNOWLEDGED','STALE') AND ack_receipt IS NOT NULL AND acknowledged_at IS NOT NULL))
);
CREATE INDEX workspace_admission_effect_pending_idx ON workspace_admission_effect_outbox(created_at, effect_id)
    WHERE delivery_status = 'PENDING';
COMMENT ON TABLE workspace_admission_effect_outbox IS '持久取消effect；只有execution持久接受或證實fence stale後才可ack。';

CREATE TABLE workspace_admission_superseded (
    registration_token VARCHAR(64) PRIMARY KEY REFERENCES workspace_admission_registration(token),
    old_effect_id VARCHAR(64) NOT NULL UNIQUE REFERENCES workspace_admission_effect_outbox(effect_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE workspace_admission_superseded IS '權威確認舊期 effect stale 且整個 Run 從未派送的不可變終止事實；阻止後續派送，不代表取消回執。';

COMMENT ON COLUMN workspace_admission_effect_outbox.intent_receipt IS '意圖持久提交的穩定識別；不代表execution已接受。';
COMMENT ON COLUMN workspace_admission_effect_outbox.ack_receipt IS 'execution權威接受或確認stale後的獨立回執。';
COMMENT ON COLUMN workspace_admission_effect_outbox.delivery_lease_id IS '每次投遞的不可重用租約；只有未過期的相同租約可確認回執。';
COMMENT ON COLUMN workspace_admission_effect_outbox.delivery_lease_until IS '以資料庫時間判定的投遞租約期限；到期後effect仍待重試。';

CREATE TABLE execution_budget_cancellation_receipt (
    effect_id VARCHAR(64) PRIMARY KEY,
    registration_token VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    partition VARCHAR(16) NOT NULL CHECK (partition IN ('REAL','SIMULATION')),
    run_id VARCHAR(64) NOT NULL,
    target_kind VARCHAR(16) NOT NULL CHECK (target_kind IN ('PENDING','CLAIMED')),
    target_id VARCHAR(64) NOT NULL,
    target_attempt_id VARCHAR(64),
    target_period_start TIMESTAMPTZ NOT NULL,
    target_period_end TIMESTAMPTZ NOT NULL,
    target_epoch_or_generation BIGINT NOT NULL CHECK (target_epoch_or_generation >= 0),
    intent_receipt VARCHAR(64) NOT NULL,
    acceptance_receipt VARCHAR(64) NOT NULL UNIQUE,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (target_period_end > target_period_start),
    CHECK ((target_kind='CLAIMED') = (target_attempt_id IS NOT NULL))
);
COMMENT ON TABLE execution_budget_cancellation_receipt IS 'Execution在權威終態交易內持久確認完整effect fence，供重送讀回同一接受回執。';
COMMENT ON COLUMN execution_budget_cancellation_receipt.intent_receipt IS 'Usage持久意圖回執，不能冒充execution接受回執。';

COMMENT ON COLUMN workspace_admission_effect_outbox.pending_id IS '建立effect時凍結的pending能力；不可從現有registration回推歷史。';
COMMENT ON COLUMN workspace_admission_effect_outbox.target_attempt_id IS '建立effect時凍結的claim所屬attempt。';

CREATE TABLE workspace_admission_finalization (
    attempt_id VARCHAR(64) PRIMARY KEY REFERENCES provider_attempt(id),
    registration_token VARCHAR(64) NOT NULL REFERENCES workspace_admission_registration(token),
    claim_id VARCHAR(64) REFERENCES workspace_admission_claim(claim_id),
    terminal_kind VARCHAR(20) NOT NULL CHECK (terminal_kind IN ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN','NOT_DISPATCHED')),
    terminal_reason VARCHAR(40) CHECK (terminal_reason IN ('CAPTURE_FAILED','ADMISSION_RECOVERY','PROMPT_CONTEXT_UNAVAILABLE','WORKSPACE_ACCESS_DENIED','RUN_TERMINATED_BEFORE_DISPATCH','PROVIDER_STREAM_ERROR','BUDGET_CANCELLATION_SUPERSEDED')),
    CHECK (terminal_reason IS DISTINCT FROM 'BUDGET_CANCELLATION_SUPERSEDED' OR terminal_kind='NOT_DISPATCHED'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','SETTLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMPTZ,
    CHECK ((state='PENDING' AND settled_at IS NULL) OR (state='SETTLED' AND settled_at IS NOT NULL)),
    CHECK ((terminal_kind='NOT_DISPATCHED') = (claim_id IS NULL))
);
CREATE INDEX workspace_admission_finalization_pending_idx ON workspace_admission_finalization(registration_token)
    WHERE state='PENDING';
COMMENT ON TABLE workspace_admission_finalization IS '在終態資料更新前持久化的精確attempt結算意圖；pending要求工作區fail-closed並可重放。';
COMMENT ON COLUMN workspace_admission_finalization.terminal_reason IS '安全且型別化的終態原因，屬不可變意圖的一部分，供重放時保留；不儲存原始例外。';
CREATE UNIQUE INDEX workspace_admission_effect_pending_target_uq ON workspace_admission_effect_outbox(registration_token,pending_id,pending_epoch) WHERE pending_id IS NOT NULL;
CREATE UNIQUE INDEX workspace_admission_effect_claim_target_uq ON workspace_admission_effect_outbox(registration_token,claim_id) WHERE claim_id IS NOT NULL;

CREATE TABLE workspace_admission_dispatch (
    attempt_id VARCHAR(64) PRIMARY KEY REFERENCES provider_attempt(id),
    registration_token VARCHAR(64) NOT NULL REFERENCES workspace_admission_registration(token),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','CLAIMED','DENIED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CHECK ((state='PENDING' AND resolved_at IS NULL) OR (state<>'PENDING' AND resolved_at IS NOT NULL))
);
CREATE INDEX workspace_admission_dispatch_pending_idx ON workspace_admission_dispatch(registration_token) WHERE state='PENDING';
COMMENT ON TABLE workspace_admission_dispatch IS 'Claim決策前持久化attempt intent；未決狀態在重啟後仍要求權威查核。';

CREATE TABLE workspace_monthly_budget_command (
    workspace_id VARCHAR(64) NOT NULL,
    partition VARCHAR(16) NOT NULL CHECK (partition IN ('REAL','SIMULATION')),
    actor_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    expected_config_version BIGINT NOT NULL CHECK (expected_config_version >= 0),
    enabled BOOLEAN NOT NULL,
    cap_usd NUMERIC(30,12),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id,partition,actor_id,idempotency_key),
    FOREIGN KEY (workspace_id,partition) REFERENCES workspace_monthly_budget(workspace_id,partition)
);
COMMENT ON TABLE workspace_monthly_budget_command IS '按可信 actor 與分帳持久去重的月預算配置命令；重播回目前摘要而不重新生效。';
