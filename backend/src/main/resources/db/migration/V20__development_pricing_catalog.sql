-- The content hash uses the same canonical payload as catalog publication.
WITH published AS (
    INSERT INTO pricing_catalog_revision (id, source_reference, source_revision, content_hash)
    VALUES ('dev-gemini-openai-2026-11-25', 'https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash (checked 2026-11-16; Standard; UTC boundaries are deployment convention)', 'dev-gemini-openai-2026-11-25', '1135ff8e3c4dcdfc95249eb785eefc8fc0fabc0f7e4bc91e01cb56dd164cad03')
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
    ('GOOGLE', 'generateContent', 'gemini-3.8-flash', 'GEMINI_API', 'gemini-3.8-flash-standard-2027', '2027-01-01T00:00:00Z', '9999-12-31T00:00:00Z', '{"input":"1.50","cache_read":"0.15","output":"7.50","thoughts":"7.50","tool_input":"1.50"}', '{"serviceTier":"STANDARD","tokenUnit":"USD_PER_1000000_TOKENS","sources":["https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash"],"verifiedDate":"2026-11-16","storageRate":"1.00","storageUnit":"USD_PER_1000000_TOKEN_HOURS"}'),
    ('OPENAI', 'responses', 'gpt-5.4-mini', 'OPENAI_API', 'gpt-5.4-mini-standard-2026-11-25', '2026-11-25T00:00:00Z', '9999-12-31T00:00:00Z', '{"input":"0.75","cache_read":"0.075","output":"4.50"}', '{"serviceTier":"STANDARD","tokenUnit":"USD_PER_1000000_TOKENS","sources":["https://developers.openai.com/api/docs/models/gpt-5.4-mini"],"verifiedDate":"2026-11-25","storageRate":null,"storageUnit":null}')
) AS entry(provider, endpoint, model, channel, price_revision, effective_from, effective_until, rates, metadata);
