-- Synthetic simulated Usage fixtures. Run only through threadplane-usage-fixture.py setup.
BEGIN;
DO $$ BEGIN
 IF current_database() <> 'bot40_usage_e2e' OR NOT EXISTS (
   SELECT 1 FROM workspace_member m JOIN workspace w ON w.id=m.workspace_id WHERE w.id='32000000-0000-4000-8000-000000000002' AND w.name='ISSUE40 synthetic usage workspace' AND m.user_id='dev-user' AND m.active
 ) THEN RAISE EXCEPTION 'wrong database or missing active fixture membership'; END IF;
 IF EXISTS (SELECT 1 FROM provider_attempt WHERE id LIKE 'issue33-browser-a-%' AND
   (workspace_id<>'32000000-0000-4000-8000-000000000002' OR actor_id IS DISTINCT FROM 'dev-user' OR provider<>'FAKE' OR model<>'issue33-browser-synthetic' OR NOT simulation))
 THEN RAISE EXCEPTION 'fixture attempt ID collision'; END IF;
 IF EXISTS (SELECT 1 FROM usage_snapshot WHERE id LIKE 'issue33-browser-s-%' AND
   (attempt_id<>'issue33-browser-a-001' OR source_layer<>'FAKE_RAW'))
 THEN RAISE EXCEPTION 'fixture snapshot ID collision'; END IF;
END $$;
INSERT INTO provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version)
SELECT 'issue33-browser-a-'||lpad(n::text,3,'0'),NULL,'32000000-0000-4000-8000-000000000002','dev-user','FAKE','generateContent','issue33-browser-synthetic','FAKE','issue33-fixture',true,'UNKNOWN',
       TIMESTAMPTZ '2026-11-13 01:00:00+08' - (n * interval '1 second'),
       TIMESTAMPTZ '2026-11-13 01:00:01+08' - (n * interval '1 second'),
       TIMESTAMPTZ '2026-11-13 01:00:02+08' - (n * interval '1 second'),
       'SUCCEEDED','UNKNOWN','UNKNOWN','MISSING',0
FROM generate_series(1,25) n ON CONFLICT (id) DO NOTHING;
INSERT INTO usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at)
SELECT 'issue33-browser-s-'||lpad(n::text,3,'0'),'issue33-browser-a-001','issue33-browser-event-'||lpad(n::text,3,'0'),
       encode(sha256(convert_to('issue33-browser-fixture-'||n::text,'UTF8')),'hex'),n,'CUMULATIVE','FAKE_RAW','issue33-fixture','issue33-fixture','issue33-fixture','COMPLETE','JSONB',
       '{"promptTokenCount":9007199254740993,"nested":{"large":123456789012345678901234567890,"text":"中文","explicitNull":null}}'::jsonb,
       TIMESTAMPTZ '2026-11-13 01:00:00+08' + (n * interval '1 millisecond')
FROM generate_series(1,25) n ON CONFLICT (id) DO NOTHING;
UPDATE provider_attempt SET usage_presence='PRESENT',capture_completeness='COMPLETE',projection_state='VALID',projection_version=1,projection_snapshot_id='issue33-browser-s-025'
WHERE id='issue33-browser-a-001' AND model='issue33-browser-synthetic' AND simulation=true AND actor_id='dev-user';
COMMIT;
