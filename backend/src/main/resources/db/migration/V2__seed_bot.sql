INSERT INTO bot (id, name, system_prompt, created_at)
VALUES (
    'bot-general',
    'General Assistant',
    'You are a helpful, accurate, and concise AI coding assistant.',
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;
