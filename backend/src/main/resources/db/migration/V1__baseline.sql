CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email VARCHAR(320) UNIQUE NOT NULL, password_hash VARCHAR(255) NOT NULL, role VARCHAR(32) NOT NULL DEFAULT 'USER', active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS analyses (id UUID PRIMARY KEY, owner_id UUID REFERENCES users(id), guest_token_hash VARCHAR(128), input_type VARCHAR(32) NOT NULL, input_text TEXT, status VARCHAR(32) NOT NULL, result_json JSONB, error_message TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS analyses_owner_date_idx ON analyses(owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS analyses_status_idx ON analyses(status, created_at);
CREATE TABLE IF NOT EXISTS audit_events (id UUID PRIMARY KEY, actor_id UUID, action VARCHAR(128) NOT NULL, entity_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

