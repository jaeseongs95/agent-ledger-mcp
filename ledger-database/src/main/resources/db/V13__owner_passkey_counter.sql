-- V13: durable WebAuthn signature counter tracking for owner passkeys (clone / rollback detection).
-- Keeps owner_passkey strictly append-only (SELECT, INSERT only for the application account) while storing the highest
-- verified signature counter in a separate table so WebAuthn4J rejects replayed or cloned non-zero counters across restarts.

CREATE TABLE owner_passkey_counter (
  credential_id VARCHAR(512) NOT NULL,
  signature_count BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_owner_passkey_counter PRIMARY KEY (credential_id),
  CONSTRAINT ck_passkey_counter_nonneg CHECK (signature_count >= 0)
);

INSERT INTO owner_passkey_counter (credential_id, signature_count, updated_at)
SELECT credential_id, signature_count, registered_at FROM owner_passkey;
