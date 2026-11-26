INSERT INTO bot (id, name, target, system_prompt, created_at, updated_at)
VALUES ('bot-general', 'General Assistant', 'LOCAL',
        'You are a helpful, accurate, and concise AI coding assistant.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
