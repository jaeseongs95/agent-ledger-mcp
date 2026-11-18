-- V8: attachments (4-table multi-file bundle layout ported from public sennard V21__attachment.sql; ANSI-006 types).
-- The bytes of an attachment are never stored in the database: they live as AES-256-GCM segments under the server's
-- attachment directory, and no column here can hold a body (no BLOB, BYTEA, CLOB or long text).
--   upload_staging     mutable staging rows, one per file of a bundle upload: (attachment_id, seq) identifies its bundle slot;
--                      (owner, operation_key, seq) makes a bundle begin idempotent.
--   attachment         immutable bundle record: id, author, creation time. Records in history point to this id only.
--   attachment_file    immutable file facts within a bundle, keyed by (attachment_id, seq).
--   attachment_storage immutable stored encrypted copy of a file, keyed by (attachment_id, seq, rev): rev 0 on finish, +1 per rekey.
-- There is no foreign key by design; all TIMESTAMP columns hold UTC instants written by the application Clock.

CREATE TABLE upload_staging (
    id                VARCHAR(36)  NOT NULL,
    attachment_id     VARCHAR(36)  NOT NULL,
    seq               INTEGER      NOT NULL,
    owner             VARCHAR(200) NOT NULL,
    operation_key     VARCHAR(128) NOT NULL,
    file_name         VARCHAR(512) NOT NULL,
    media_type        VARCHAR(255) NOT NULL,
    declared_size     BIGINT       NOT NULL,
    declared_sha256   VARCHAR(64)  NOT NULL,
    received_bytes    BIGINT       NOT NULL,
    stored_bytes      BIGINT       NOT NULL,
    next_sequence     BIGINT       NOT NULL,
    last_chunk_sha256 VARCHAR(64),
    storage_key       VARCHAR(32)  NOT NULL,
    key_id            VARCHAR(64)  NOT NULL,
    state             VARCHAR(16)  NOT NULL,
    expires_at        TIMESTAMP    NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_upload_staging PRIMARY KEY (id),
    CONSTRAINT uq_upload_staging_operation UNIQUE (owner, operation_key, seq),
    CONSTRAINT uq_upload_staging_bundle UNIQUE (attachment_id, seq),
    CONSTRAINT uq_upload_staging_key UNIQUE (storage_key),
    CONSTRAINT ck_upload_staging_seq CHECK (seq >= 0),
    CONSTRAINT ck_upload_staging_state CHECK (state IN ('STAGING', 'COMPLETED')),
    CONSTRAINT ck_upload_staging_sizes CHECK (declared_size >= 0 AND received_bytes >= 0 AND stored_bytes >= 0 AND next_sequence >= 0)
);
CREATE INDEX ix_upload_staging_state_expiry ON upload_staging (state, expires_at);
CREATE INDEX ix_upload_staging_bundle ON upload_staging (attachment_id, seq);

CREATE TABLE attachment (
    id         VARCHAR(36)  NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_attachment PRIMARY KEY (id)
);
CREATE INDEX ix_attachment_author ON attachment (created_by, created_at);

CREATE TABLE attachment_file (
    attachment_id  VARCHAR(36)  NOT NULL,
    seq            INTEGER      NOT NULL,
    file_name      VARCHAR(512) NOT NULL,
    media_type     VARCHAR(255) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    content_sha256 VARCHAR(64)  NOT NULL,
    CONSTRAINT pk_attachment_file PRIMARY KEY (attachment_id, seq),
    CONSTRAINT ck_attachment_file_numbers CHECK (seq >= 0 AND size_bytes >= 0)
);

CREATE TABLE attachment_storage (
    attachment_id VARCHAR(36) NOT NULL,
    seq           INTEGER     NOT NULL,
    rev           INTEGER     NOT NULL,
    storage_key   VARCHAR(32) NOT NULL,
    key_id        VARCHAR(64) NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    CONSTRAINT pk_attachment_storage PRIMARY KEY (attachment_id, seq, rev),
    CONSTRAINT uq_attachment_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_attachment_storage_numbers CHECK (seq >= 0 AND rev >= 0)
);

ALTER TABLE owner_audit DROP CONSTRAINT ck_owner_audit_operation;
ALTER TABLE owner_audit ADD CONSTRAINT ck_owner_audit_operation CHECK (operation IN ('GRANT_ISSUED', 'GRANT_REVOKED',
  'KEY_REGISTERED', 'KEY_REPLACED', 'KEY_REVOKED', 'TOKEN_ISSUED', 'TOKEN_REVOKED', 'CODE_SET', 'PROJECT_ADDED',
  'DRIVER_REGISTERED', 'IMPORT', 'PASSKEY_CODE_ISSUED', 'PASSKEY_REGISTERED', 'PASSKEY_REVOKED', 'PASSKEY_RESET',
  'ATTACHMENT_REKEY', 'ORPHANS_PURGED'));

ALTER TABLE history ADD COLUMN attachment_id VARCHAR(36);
CREATE INDEX ix_history_attachment ON history (attachment_id);
