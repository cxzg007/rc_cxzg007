CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    url TEXT NOT NULL,
    method VARCHAR(8) NOT NULL,
    headers JSONB NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending','processing','succeeded','failed')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_status_code INTEGER,
    last_error VARCHAR(128),
    replay_of UUID REFERENCES notifications(id),
    UNIQUE (client_id, request_id),
    CHECK ((status = 'processing') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS notifications_due_idx ON notifications (next_attempt_at, id) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS notifications_expired_idx ON notifications (lease_until) WHERE status = 'processing';
CREATE INDEX IF NOT EXISTS notifications_client_failed_idx ON notifications (client_id, created_at DESC, id) WHERE status = 'failed';
CREATE TABLE IF NOT EXISTS attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id),
    number INTEGER NOT NULL,
    lease_token UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('processing','succeeded','retry','failed','unknown')),
    status_code INTEGER,
    error VARCHAR(128),
    UNIQUE (notification_id, number)
);
