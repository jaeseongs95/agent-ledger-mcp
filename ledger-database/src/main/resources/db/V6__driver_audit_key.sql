-- V6: the key of a driver registration in the audit trail (review finding TX-03). The row that tells the owner's audit trail
-- about a registration that was made while the schema was not ready is written by the next migrate or setup, which first looked
-- for a row of that registration and wrote one when it found none. Two runs at the same time could both find none and write
-- the row twice, because nothing in the database said that it may exist once only. The registration is now claimed by a key that the database keeps
-- unique: the key is the SHA-256 (64 hex characters, so it fits an index on every DBMS) of the class of the driver, its jar, the
-- digest of the jar and the time of the registration, and the run that cannot insert it knows that another run recorded it.

CREATE TABLE driver_audit_key (
  registration_key VARCHAR(64) NOT NULL,
  CONSTRAINT pk_driver_audit_key PRIMARY KEY (registration_key)
);
