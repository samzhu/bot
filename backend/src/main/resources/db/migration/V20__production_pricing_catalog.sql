-- The content hash uses the same canonical payload as catalog publication.
WITH published AS (
    INSERT INTO pricing_catalog_revision (id, source_reference, source_revision, content_hash)
    VALUES ('gemini-standard-2026-11-16', 'https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash (checked 2026-11-16; Standard; UTC boundaries are deployment convention)', 'gemini-standard-2026-11-16', '0a0e4a13103ea27bffdee2a02623f42764a442f90953b45be40cceffd04db9ec')
    ON CONFLICT (source_revision) DO NOTHING
    RETURNING id
)
INSERT INTO pricing_catalog_entry
    (id, revision_id, provider, endpoint, model, channel, price_revision,
     effective_from, effective_until, rates, metadata)
SELECT gen_random_uuid()::text, published.id, entry.provider, entry.endpoint, entry.model,
       entry.channel, entry.price_revision, entry.effective_from::timestamptz,
       entry.effective_until::timestamptz, entry.rates::jsonb, entry.metadata::jsonb
FROM published CROSS JOIN (VALUES
    ('GOOGLE', 'generateContent', 'gemini-3.8-flash', 'GEMINI_API', 'gemini-3.8-flash-standard-2026', '2026-11-16T00:00:00Z', '2027-01-01T00:00:00Z', '{"input":"0.75","cache_read":"0.075","output":"3.75","thoughts":"3.75","tool_input":"0.75"}', '{"serviceTier":"STANDARD","tokenUnit":"USD_PER_1000000_TOKENS","sources":["https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash"],"verifiedDate":"2026-11-16","storageRate":"0.50","storageUnit":"USD_PER_1000000_TOKEN_HOURS"}'),
    ('GOOGLE', 'generateContent', 'gemini-3.8-flash', 'GEMINI_API', 'gemini-3.8-flash-standard-2027', '2027-01-01T00:00:00Z', '9999-12-31T00:00:00Z', '{"input":"1.50","cache_read":"0.15","output":"7.50","thoughts":"7.50","tool_input":"1.50"}', '{"serviceTier":"STANDARD","tokenUnit":"USD_PER_1000000_TOKENS","sources":["https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash"],"verifiedDate":"2026-11-16","storageRate":"1.00","storageUnit":"USD_PER_1000000_TOKEN_HOURS"}')
) AS entry(provider, endpoint, model, channel, price_revision, effective_from, effective_until, rates, metadata);
