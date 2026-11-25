-- V7: the passkeys of the owner (passkey sign-in and the approval of every owner write in the admin web) and the one-time codes
-- that enrol them. The tables are the storage of the Spring Security WebAuthn support (UserCredentialRepository and
-- PublicKeyCredentialUserEntityRepository are implemented on them), shaped by the rules of this schema: no BLOB column (every
-- byte string is base64url text with a bound length), no boolean column (the three flags are 0 or 1), no foreign key, UTC
-- timestamps, ANSI SQL only.
--
-- The history of a credential is append-only: owner_passkey rows are inserted and never changed or deleted (the signature
-- counter and the time of last use are not kept: a synced passkey does not count, and an approval is bound to one action by its
-- one-use challenge), and closing a passkey inserts a row into owner_passkey_revocation. A passkey is live while it has no
-- revocation row.
--
-- owner_passkey_user is the one owner (the user entity of WebAuthn). owner_passkey_code holds the one-time enrolment code that the
-- owner asks for on the server (ledger-cli passkey-code): only the SHA-256 of the code is stored, a code is used once (used_at is
-- set by a conditional UPDATE that affects no row for a used or an expired code) and lives a few minutes (expires_at).
--
-- The owner's audit trail gets the four constants of the passkey operations: its check constraint is replaced.

CREATE TABLE owner_passkey_user (
  user_id VARCHAR(100) NOT NULL,
  user_name VARCHAR(100) NOT NULL,
  display_name VARCHAR(200),
  CONSTRAINT pk_owner_passkey_user PRIMARY KEY (user_id)
);

CREATE TABLE owner_passkey (
  credential_id VARCHAR(512) NOT NULL,
  user_id VARCHAR(100) NOT NULL,
  public_key VARCHAR(1024) NOT NULL,
  signature_count BIGINT NOT NULL,
  uv_initialized INTEGER NOT NULL,
  backup_eligible INTEGER NOT NULL,
  backup_state INTEGER NOT NULL,
  transports VARCHAR(200),
  attestation_object VARCHAR(4096) NOT NULL,
  label VARCHAR(60) NOT NULL,
  fingerprint VARCHAR(19) NOT NULL,
  registered_by VARCHAR(300) NOT NULL,
  registered_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_owner_passkey PRIMARY KEY (credential_id),
  CONSTRAINT ck_owner_passkey_flags CHECK (uv_initialized IN (0, 1) AND backup_eligible IN (0, 1) AND backup_state IN (0, 1))
);

CREATE TABLE owner_passkey_revocation (
  credential_id VARCHAR(512) NOT NULL,
  revoked_by VARCHAR(300) NOT NULL,
  revoked_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_owner_passkey_revocation PRIMARY KEY (credential_id)
);

CREATE TABLE owner_passkey_code (
  code_hash VARCHAR(64) NOT NULL,
  issued_by VARCHAR(300) NOT NULL,
  issued_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP,
  CONSTRAINT pk_owner_passkey_code PRIMARY KEY (code_hash)
);

ALTER TABLE owner_audit DROP CONSTRAINT ck_owner_audit_operation;

ALTER TABLE owner_audit ADD CONSTRAINT ck_owner_audit_operation CHECK (operation IN ('GRANT_ISSUED', 'GRANT_REVOKED',
  'KEY_REGISTERED', 'KEY_REPLACED', 'KEY_REVOKED', 'CODE_SET', 'PROJECT_ADDED', 'DRIVER_REGISTERED', 'IMPORT',
  'PASSKEY_CODE_ISSUED', 'PASSKEY_REGISTERED', 'PASSKEY_REVOKED', 'PASSKEY_RESET'));
