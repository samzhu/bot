CREATE TABLE pricing_catalog_revision (
 id VARCHAR(64) PRIMARY KEY,
 generation BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
 source_reference TEXT NOT NULL, source_revision VARCHAR(255) NOT NULL UNIQUE, content_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE pricing_catalog_entry (
 id VARCHAR(64) PRIMARY KEY, revision_id VARCHAR(64) NOT NULL REFERENCES pricing_catalog_revision(id),
 provider VARCHAR(64) NOT NULL, endpoint VARCHAR(128) NOT NULL, model VARCHAR(255) NOT NULL, channel VARCHAR(128) NOT NULL,
 price_revision VARCHAR(255) NOT NULL, effective_from TIMESTAMPTZ NOT NULL, effective_until TIMESTAMPTZ NOT NULL,
 rates JSONB NOT NULL, CHECK (effective_from < effective_until)
);
ALTER TABLE provider_attempt ADD COLUMN catalog_marker VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNPRICED'
 CHECK (catalog_marker IN ('LEGACY_UNPRICED','NO_CATALOG','CAPTURED'));
ALTER TABLE provider_attempt ADD COLUMN catalog_revision_id VARCHAR(64) REFERENCES pricing_catalog_revision(id);
ALTER TABLE provider_attempt ADD CHECK ((catalog_marker='CAPTURED') = (catalog_revision_id IS NOT NULL));
COMMENT ON TABLE pricing_catalog_revision IS '不可變受控價格目錄發布版本，activation 前持久化。';
COMMENT ON COLUMN pricing_catalog_revision.source_reference IS '可稽核的價格來源參照。';
COMMENT ON TABLE pricing_catalog_entry IS '目錄內供應商、模型、渠道與期間的不可變價格。';
COMMENT ON COLUMN pricing_catalog_entry.rates IS 'USD 每百萬單位的十進位字串費率。';
COMMENT ON COLUMN provider_attempt.catalog_marker IS '派送時固定 CAPTURED、NO_CATALOG；舊紀錄 LEGACY_UNPRICED。';
COMMENT ON COLUMN provider_attempt.catalog_revision_id IS '派送決策時已啟用之不可變目錄版本。';
CREATE TABLE usage_price_snapshot (
 id VARCHAR(64) PRIMARY KEY,
 attempt_id VARCHAR(64) NOT NULL UNIQUE REFERENCES provider_attempt(id),
 UNIQUE (attempt_id,id),
 selection_state VARCHAR(16) NOT NULL CHECK (selection_state IN ('MATCHED','MISSING','OVERLAPPING','LEGACY_UNPRICED')),
 provider VARCHAR(64) NOT NULL, endpoint VARCHAR(128) NOT NULL,
 model VARCHAR(255) NOT NULL, channel VARCHAR(128) NOT NULL,
 price_revision VARCHAR(255), rates JSONB, currency VARCHAR(3) NOT NULL DEFAULT 'USD',
 catalog_revision_id VARCHAR(64) REFERENCES pricing_catalog_revision(id),
 catalog_entry_id VARCHAR(64) REFERENCES pricing_catalog_entry(id),
 source_reference TEXT, effective_from TIMESTAMPTZ, effective_until TIMESTAMPTZ,
 CHECK ((selection_state='MATCHED' AND price_revision IS NOT NULL AND rates IS NOT NULL) OR
        (selection_state<>'MATCHED' AND price_revision IS NULL AND rates IS NULL))
);
CREATE TABLE usage_cost_estimate (
 id VARCHAR(64) PRIMARY KEY,
 attempt_id VARCHAR(64) NOT NULL REFERENCES provider_attempt(id),
 source_version BIGINT NOT NULL,
 source_snapshot_id VARCHAR(64),
 price_snapshot_id VARCHAR(64) NOT NULL,
 calculator_version VARCHAR(128) NOT NULL,
 status VARCHAR(24) NOT NULL CHECK (status IN ('UNPRICED','COMPLETE','PARTIAL','UNKNOWN','NOT_DISPATCHED')),
 known_subtotal NUMERIC(30,12), complete_amount NUMERIC(30,12),
 details JSONB NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (attempt_id,id),
 UNIQUE NULLS NOT DISTINCT (attempt_id, source_version, source_snapshot_id, price_snapshot_id, calculator_version),
 FOREIGN KEY (attempt_id,price_snapshot_id) REFERENCES usage_price_snapshot(attempt_id,id),
 FOREIGN KEY (attempt_id,source_snapshot_id) REFERENCES usage_snapshot(attempt_id,id),
 CHECK ((status='COMPLETE' AND complete_amount IS NOT NULL) OR (status<>'COMPLETE' AND complete_amount IS NULL))
);
CREATE TABLE usage_cost_current (
 attempt_id VARCHAR(64) PRIMARY KEY REFERENCES provider_attempt(id),
 estimate_id VARCHAR(64) NOT NULL UNIQUE,
 FOREIGN KEY (attempt_id,estimate_id) REFERENCES usage_cost_estimate(attempt_id,id)
);
COMMENT ON TABLE usage_price_snapshot IS '每次嘗試固定選定的價格版本及費率；缺價與重疊也固定記錄。';
COMMENT ON COLUMN usage_price_snapshot.selection_state IS 'MATCHED、MISSING 或 OVERLAPPING；不以零代表缺價。';
COMMENT ON COLUMN usage_price_snapshot.rates IS '當次不可變 USD 每百萬單位價格；未匹配為 SQL NULL。';
COMMENT ON TABLE usage_cost_estimate IS '按來源版本追加的不可變估算與計算依據，不是供應商帳單。';
COMMENT ON COLUMN usage_cost_estimate.source_version IS '計算時使用的 usage projection 版本。';
COMMENT ON COLUMN usage_cost_estimate.source_snapshot_id IS '有效原生 usage 快照；來源未知時為 NULL。';
COMMENT ON COLUMN usage_cost_estimate.known_subtotal IS '可表示且已定價分項的小計，無已知項時為 NULL。';
COMMENT ON COLUMN usage_cost_estimate.complete_amount IS '宣告估算範圍完整時的金額，否則為 NULL。';
COMMENT ON COLUMN usage_cost_estimate.details IS '分項、精確計算證據、來源與未知原因；不保存 prompt。';
COMMENT ON TABLE usage_cost_current IS '每個 attempt 的單一生效估算指標；來源版本落後時不視為新結果。';

COMMENT ON COLUMN usage_price_snapshot.catalog_revision_id IS '派送時固定的不可變目錄版本，無目錄或舊紀錄為 NULL。';
COMMENT ON COLUMN usage_price_snapshot.catalog_entry_id IS '匹配價格的不可變目錄項；未匹配為 NULL。';
COMMENT ON COLUMN usage_price_snapshot.source_reference IS '固定價格來源；缺價時為 NULL。';
COMMENT ON COLUMN usage_price_snapshot.effective_from IS '價格生效起點。';
COMMENT ON COLUMN usage_price_snapshot.effective_until IS '價格生效終點（不含）。';

COMMENT ON COLUMN pricing_catalog_revision.source_revision IS '受控來源穩定版本，重送不可產生較新 generation。';
COMMENT ON COLUMN pricing_catalog_revision.content_hash IS '用於相同來源版本 payload 冪等與衝突檢查。';
