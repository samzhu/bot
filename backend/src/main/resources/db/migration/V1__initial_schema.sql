-- Table: schema_version
CREATE TABLE IF NOT EXISTS schema_version (
    script_name VARCHAR(255) PRIMARY KEY,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table: bot
CREATE TABLE IF NOT EXISTS bot (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    system_prompt TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table: conversation
CREATE TABLE IF NOT EXISTS conversation (
    id VARCHAR(64) PRIMARY KEY,
    bot_id VARCHAR(64) NOT NULL REFERENCES bot(id),
    title VARCHAR(255) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conv_user_created ON conversation (user_id, created_at DESC, id DESC);

-- Table: agent_run (created before message to allow FK from message.run_id)
CREATE TABLE IF NOT EXISTS agent_run (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    client_request_id VARCHAR(128) NOT NULL,
    prompt_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Constraint: single active run per conversation
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_run_per_conv
ON agent_run (conversation_id)
WHERE status IN ('PENDING', 'RUNNING');

-- Constraint: deduplication per conversation and client_request_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_client_request_per_conv
ON agent_run (conversation_id, client_request_id);

CREATE INDEX IF NOT EXISTS idx_run_conv_created ON agent_run (conversation_id, created_at DESC, id DESC);

-- Table: message (references conversation and agent_run)
CREATE TABLE IF NOT EXISTS message (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content TEXT NOT NULL,
    interrupted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_run_role UNIQUE (run_id, role)
);

CREATE INDEX IF NOT EXISTS idx_msg_conv_created ON message (conversation_id, created_at ASC, id ASC);
CREATE INDEX IF NOT EXISTS idx_msg_run_id ON message (run_id);
