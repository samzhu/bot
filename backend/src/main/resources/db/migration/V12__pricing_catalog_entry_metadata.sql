ALTER TABLE pricing_catalog_entry ADD COLUMN metadata JSONB;
COMMENT ON COLUMN pricing_catalog_entry.metadata IS '可選的受控目錄來源、服務層級、單位及查核資料；舊資料未知時保留 NULL。';
