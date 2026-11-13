-- Automatic dispatch prices remain unique; audited reconciliations retain their own immutable snapshots.
ALTER TABLE usage_price_snapshot DROP CONSTRAINT usage_price_snapshot_attempt_id_key;
ALTER TABLE usage_price_snapshot ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'AUTO_DISPATCH'
    CHECK (origin IN ('AUTO_DISPATCH', 'AUDITED_RECONCILIATION'));
CREATE UNIQUE INDEX usage_price_snapshot_auto_dispatch_unique
    ON usage_price_snapshot(attempt_id) WHERE origin = 'AUTO_DISPATCH';
-- A manager declaration can precede automatic pricing and must not invent an original price.
ALTER TABLE usage_cost_estimate ALTER COLUMN price_snapshot_id DROP NOT NULL;
ALTER TABLE usage_cost_estimate ADD COLUMN publication_kind VARCHAR(24) NOT NULL DEFAULT 'AUTO_CURRENT'
    CHECK (publication_kind IN ('AUTO_CURRENT','AUTO_CANDIDATE','AUDITED'));
COMMENT ON COLUMN usage_cost_estimate.publication_kind IS '原生自動結果、保留待審候選或稽核修訂；候選不自動成為 current。';

CREATE TABLE usage_cost_manual_source_checkpoint (
    attempt_id VARCHAR(64) PRIMARY KEY REFERENCES provider_attempt(id),
    last_processed_projection_version BIGINT NOT NULL
);
COMMENT ON TABLE usage_cost_manual_source_checkpoint IS '持久記錄人工 current 已觀察的來源版本，避免背景工作反覆掃描並覆蓋人工修訂。';

CREATE TABLE usage_cost_revision_audit (
    id VARCHAR(64) PRIMARY KEY,
    attempt_id VARCHAR(64) NOT NULL REFERENCES provider_attempt(id),
    estimate_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('MANUAL_ESTIMATE', 'RECONCILIATION')),
    idempotency_key VARCHAR(128) NOT NULL,
    command_payload JSONB NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    evidence_reference VARCHAR(1024),
    reason VARCHAR(2048) NOT NULL,
    UNIQUE (attempt_id, idempotency_key),
    FOREIGN KEY (attempt_id, estimate_id) REFERENCES usage_cost_estimate(attempt_id, id)
);
COMMENT ON COLUMN usage_price_snapshot.origin IS '派送固定價格或經稽核重新對帳所追加的價格快照。';
COMMENT ON TABLE usage_cost_revision_audit IS '追加式費用修訂命令與操作者稽核，不代表供應商帳單。';
COMMENT ON COLUMN usage_cost_revision_audit.command_payload IS '正規化命令，用於重新驗權後的冪等重送與衝突判定。';
COMMENT ON COLUMN usage_cost_revision_audit.evidence_reference IS '管理員提供的佐證參照；系統不抓取或驗證其內容。';
