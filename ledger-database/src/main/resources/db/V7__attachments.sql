-- V7: attachments. The body of a review report or of any other evidence is never stored in the database: a file in the
-- attachment directory holds it (encrypted, under a random name), and these tables hold only the metadata and the name of the
-- file (the storage key). No column holds the body (no BLOB, BYTEA, CLOB or long text), and no foreign key joins the tables (a
-- history row names an attachment by its id and attachment_file names the attachment by its id; the application checks the ids
-- when it writes the rows).
--
-- upload_staging is the one table of the attachments that changes: an upload that is not finished (STAGING), and an upload
-- that was finished (COMPLETED) until the reaper removes the row. attachment and attachment_file are written once and never
-- changed or deleted: the application account has only SELECT and INSERT on them (dml-grants.sql), so a body that was kept as
-- evidence stays what it was. The file of an attachment is the latest row of attachment_file: the finish adds the first row, a
-- re-encryption (rekey) adds the next one, and the file of an older row is a replaced file.
-- Ids are text (UUID) made by the application. Times are UTC TIMESTAMP values written by the application clock.

CREATE TABLE upload_staging (
  id VARCHAR(36) NOT NULL,
  owner VARCHAR(200) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  media_type VARCHAR(127) NOT NULL,
  declared_size BIGINT NOT NULL,
  declared_sha256 VARCHAR(64) NOT NULL,
  received_bytes BIGINT NOT NULL,
  stored_bytes BIGINT NOT NULL,
  next_sequence BIGINT NOT NULL,
  last_chunk_sha256 VARCHAR(64),
  storage_key VARCHAR(32) NOT NULL,
  key_id VARCHAR(64) NOT NULL,
  state VARCHAR(10) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_upload_staging PRIMARY KEY (id),
  CONSTRAINT uq_upload_staging_operation UNIQUE (owner, operation_key),
  CONSTRAINT uq_upload_staging_storage UNIQUE (storage_key),
  CONSTRAINT ck_upload_staging_state CHECK (state IN ('STAGING', 'COMPLETED')),
  CONSTRAINT ck_upload_staging_sizes CHECK (declared_size >= 0 AND received_bytes >= 0 AND stored_bytes >= 0
    AND next_sequence >= 0)
);
CREATE INDEX ix_upload_staging_expires ON upload_staging (state, expires_at);

CREATE TABLE attachment (
  id VARCHAR(36) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  media_type VARCHAR(127) NOT NULL,
  size_bytes BIGINT NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_attachment PRIMARY KEY (id),
  CONSTRAINT ck_attachment_size CHECK (size_bytes >= 0)
);
CREATE INDEX ix_attachment_author ON attachment (created_by, created_at);

CREATE TABLE attachment_file (
  id VARCHAR(36) NOT NULL,
  attachment_id VARCHAR(36) NOT NULL,
  storage_key VARCHAR(32) NOT NULL,
  key_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_attachment_file PRIMARY KEY (id),
  CONSTRAINT uq_attachment_file_storage UNIQUE (storage_key)
);
CREATE INDEX ix_attachment_file_attachment ON attachment_file (attachment_id, created_at);

-- The attachment a history row points to: the id of a row of attachment (no foreign key; the write checks it).
ALTER TABLE history ADD COLUMN attachment_id VARCHAR(36);
CREATE INDEX ix_history_attachment ON history (attachment_id);
