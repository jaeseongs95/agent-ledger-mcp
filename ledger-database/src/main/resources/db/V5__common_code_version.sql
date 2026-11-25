-- V5: common_code optimistic locking version and vendor-independent standard MCP session bearer tokens.
-- session_token binds an opaque per-caller HTTP Bearer token (stored only as its SHA-256 hash) to an AuthorId
-- so any standard MCP client can authenticate writes without a proprietary client hook.

ALTER TABLE common_code ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

CREATE TABLE session_token (
  id BIGINT NOT NULL,
  author_id VARCHAR(200) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  token_prefix VARCHAR(16) NOT NULL,
  issued_by VARCHAR(200) NOT NULL,
  issued_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  revoked_at TIMESTAMP,
  last_used_at TIMESTAMP,
  note VARCHAR(500),
  CONSTRAINT pk_session_token PRIMARY KEY (id),
  CONSTRAINT uq_session_token_hash UNIQUE (token_hash),
  CONSTRAINT ck_session_token_expiry CHECK (expires_at IS NULL OR expires_at > issued_at),
  CONSTRAINT ck_session_token_revoked CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);
CREATE INDEX ix_session_token_author ON session_token (author_id, id);
