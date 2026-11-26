CREATE TABLE provider_attempt (
 id VARCHAR(64) PRIMARY KEY,
 run_id VARCHAR(64) REFERENCES chat_run(id),
 workspace_id VARCHAR(64) NOT NULL REFERENCES workspace(id),
 actor_id VARCHAR(64),
 provider VARCHAR(64) NOT NULL,
 endpoint VARCHAR(128) NOT NULL,
 model VARCHAR(255) NOT NULL,
 channel VARCHAR(128) NOT NULL,
 adapter_version VARCHAR(128) NOT NULL,
 simulation BOOLEAN NOT NULL,
 attempt_coverage VARCHAR(16) NOT NULL CHECK (attempt_coverage IN ('VERIFIED','UNKNOWN')),
 prepared_at TIMESTAMPTZ NOT NULL,
 started_at TIMESTAMPTZ,
 finished_at TIMESTAMPTZ,
 execution_outcome VARCHAR(16) NOT NULL CHECK (execution_outcome IN ('IN_PROGRESS','SUCCEEDED','FAILED','CANCELLED','UNKNOWN','NOT_DISPATCHED')),
 execution_reason VARCHAR(128),
 CHECK (execution_outcome <> 'NOT_DISPATCHED' OR started_at IS NULL),
 usage_presence VARCHAR(16) NOT NULL CHECK (usage_presence IN ('PENDING','PRESENT','ABSENT','UNKNOWN')),
 capture_completeness VARCHAR(16) NOT NULL CHECK (capture_completeness IN ('COMPLETE','INCOMPLETE','UNKNOWN')),
 projection_state VARCHAR(16) NOT NULL CHECK (projection_state IN ('VALID','MISSING','UNKNOWN')),
 projection_error VARCHAR(128),
 projection_version BIGINT NOT NULL DEFAULT 0,
 projection_snapshot_id VARCHAR(64),
 provider_request_id VARCHAR(255),
 provider_response_id VARCHAR(255)
);
CREATE TABLE usage_snapshot (
 id VARCHAR(64) PRIMARY KEY,
 attempt_id VARCHAR(64) NOT NULL REFERENCES provider_attempt(id),
 event_id VARCHAR(255) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 ordinal BIGINT NOT NULL,
 semantic VARCHAR(16) NOT NULL CHECK (semantic IN ('CUMULATIVE','FINAL','DELTA','UNKNOWN')),
 source_layer VARCHAR(32) NOT NULL,
 schema_version VARCHAR(128) NOT NULL,
 adapter_version VARCHAR(128) NOT NULL,
 capture_version VARCHAR(128) NOT NULL,
 capture_completeness VARCHAR(16) NOT NULL CHECK (capture_completeness IN ('COMPLETE','INCOMPLETE','UNKNOWN')),
 raw_state VARCHAR(32) NOT NULL CHECK (raw_state IN ('JSONB','TEXT_FALLBACK','UNAVAILABLE')),
 raw_usage JSONB,
 raw_usage_text TEXT,
 capture_reason VARCHAR(128),
 captured_at TIMESTAMPTZ NOT NULL,
 UNIQUE (attempt_id,event_id),
 UNIQUE (attempt_id,id),
 CHECK ((raw_state='JSONB' AND raw_usage IS NOT NULL AND raw_usage_text IS NULL) OR
        (raw_state='TEXT_FALLBACK' AND raw_usage IS NULL AND raw_usage_text IS NOT NULL) OR
        (raw_state='UNAVAILABLE' AND raw_usage IS NULL AND raw_usage_text IS NULL))
);
ALTER TABLE provider_attempt ADD CONSTRAINT fk_attempt_projection FOREIGN KEY (id,projection_snapshot_id) REFERENCES usage_snapshot(attempt_id,id);
CREATE INDEX idx_attempt_scope_time ON provider_attempt(workspace_id,actor_id,prepared_at DESC,id DESC);
CREATE INDEX idx_snapshot_attempt_order ON usage_snapshot(attempt_id,ordinal DESC,id DESC);
COMMENT ON TABLE provider_attempt IS '一次模型供應商派送及可信歸屬，與整次 Run 分開。';
COMMENT ON COLUMN provider_attempt.run_id IS '觸發執行；可信背景工作可無 Run。';
COMMENT ON COLUMN provider_attempt.workspace_id IS '派送時的工作區歸屬快照。';
COMMENT ON COLUMN provider_attempt.actor_id IS '可信發起人；可信背景工作可未歸屬。';
COMMENT ON COLUMN provider_attempt.execution_outcome IS '模型派送生命週期，與 usage 完整性分開。';
COMMENT ON COLUMN provider_attempt.usage_presence IS 'PENDING、PRESENT、ABSENT 或 UNKNOWN；ABSENT 不表示零。';
COMMENT ON COLUMN provider_attempt.projection_snapshot_id IS '供稽核的最後投影快照；只有 VALID 時代表有效投影。';
COMMENT ON COLUMN provider_attempt.projection_version IS '投影變化的樂觀版本，供一致讀取與後續計價比較。';
COMMENT ON COLUMN provider_attempt.simulation IS '模擬執行不得混入真實費用。';
COMMENT ON TABLE usage_snapshot IS '不可變原生 usage 快照，不保存完整 response 或 prompt。';
COMMENT ON COLUMN usage_snapshot.event_id IS '同 attempt 中由 adapter 提供的穩定事件識別碼。';
COMMENT ON COLUMN usage_snapshot.ordinal IS '來源序列，非接收時間。';
COMMENT ON COLUMN usage_snapshot.raw_usage IS 'PostgreSQL 可表示之原生 usage JSON 值。';
COMMENT ON COLUMN usage_snapshot.raw_usage_text IS 'JSONB 不可表示但已完整取得之合法 usage 子樹文字。';
COMMENT ON COLUMN usage_snapshot.raw_state IS 'JSONB、TEXT_FALLBACK 或 UNAVAILABLE；SQL NULL 與 JSON null 有別。';
COMMENT ON COLUMN usage_snapshot.capture_reason IS '安全失敗原因，不含回應片段或憑證。';
COMMENT ON COLUMN provider_attempt.execution_reason IS '安全的執行原因；未派送時記錄授權拒絕原因。';
COMMENT ON COLUMN provider_attempt.attempt_coverage IS '已觀察的派送是否可完整逐 attempt 計量；UNKNOWN 不代表完整。';
COMMENT ON COLUMN provider_attempt.id IS '模型派送嘗試的穩定識別碼。';
COMMENT ON COLUMN provider_attempt.provider IS '原生用量來源供應商。';
COMMENT ON COLUMN provider_attempt.endpoint IS '該次呼叫使用的供應商端點語意。';
COMMENT ON COLUMN provider_attempt.model IS '派送時使用的模型識別碼。';
COMMENT ON COLUMN provider_attempt.channel IS '部署或供應商呼叫渠道，供後續價格匹配。';
COMMENT ON COLUMN provider_attempt.adapter_version IS '捕獲 adapter 的版本。';
COMMENT ON COLUMN provider_attempt.prepared_at IS '嘗試登記時間，不隨派送變動，用於穩定分頁。';
COMMENT ON COLUMN provider_attempt.started_at IS '服務端派送決定時間，未派送為 NULL，供後續費用歸期。';
COMMENT ON COLUMN provider_attempt.finished_at IS '嘗試終態確認時間，未確認時為 NULL。';
COMMENT ON COLUMN provider_attempt.capture_completeness IS '目前投影的來源捕獲完整性，與使用量最終性不同。';
COMMENT ON COLUMN provider_attempt.projection_state IS 'VALID、MISSING 或 UNKNOWN；VALID 不代表價格完整。';
COMMENT ON COLUMN provider_attempt.projection_error IS '投影不可用或不確定的安全原因代碼。';
COMMENT ON COLUMN provider_attempt.provider_request_id IS '來源提供的請求 ID；未提供時為 NULL。';
COMMENT ON COLUMN provider_attempt.provider_response_id IS '來源提供的回應 ID；未提供時為 NULL。';
COMMENT ON COLUMN usage_snapshot.id IS '原生用量快照的穩定識別碼。';
COMMENT ON COLUMN usage_snapshot.attempt_id IS '所屬模型派送嘗試。';
COMMENT ON COLUMN usage_snapshot.payload_hash IS '用於識別同事件重送內容是否一致的摘要，不作事件身分。';
COMMENT ON COLUMN usage_snapshot.semantic IS '來源快照語意：CUMULATIVE、FINAL、DELTA 或 UNKNOWN。';
COMMENT ON COLUMN usage_snapshot.source_layer IS '取得用量的來源層，如 SDK_NATIVE 或 FAKE_RAW。';
COMMENT ON COLUMN usage_snapshot.schema_version IS '來源用量格式版本。';
COMMENT ON COLUMN usage_snapshot.adapter_version IS '取得該快照的 adapter 版本。';
COMMENT ON COLUMN usage_snapshot.capture_version IS '捕獲與序列化策略版本。';
COMMENT ON COLUMN usage_snapshot.capture_completeness IS '來源用量子樹的捕獲完整性，與 JSONB 支援和最終性獨立。';
COMMENT ON COLUMN usage_snapshot.captured_at IS '快照捕獲時間，不作來源序列。';
