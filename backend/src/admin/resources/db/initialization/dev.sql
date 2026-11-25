INSERT INTO pg_temp.release_initialization (payload) VALUES ($bootstrap$
{
  "environment": "dev",
  "bot": {
    "id": "bot-general",
    "name": "General Assistant",
    "target": "LOCAL",
    "systemPrompt": "You are a helpful, accurate, and concise AI coding assistant."
  }
}
$bootstrap$::jsonb);
