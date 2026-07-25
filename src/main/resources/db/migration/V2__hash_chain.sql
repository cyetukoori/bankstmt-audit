-- Tamper-evidence: chain each audit row to the previous one with a SHA-256 hash. Any edit or
-- deletion of a sealed row breaks the chain and is detectable on verification.
--   entry_hash = SHA-256( prev_hash || '\n' || canonical(id, occurred_at, tenant, user, action,
--                          subject_type, subject_id, details) )
-- `seq` is a strictly increasing, gap-free order assigned by the (single-writer) consumer; the
-- UNIQUE index makes two writers racing for the same position collide (one is redelivered by SQS
-- and re-seals at the next position) so the chain never forks.
ALTER TABLE audit_events
    ADD COLUMN seq        BIGINT   NULL,
    ADD COLUMN prev_hash  CHAR(64) NULL,
    ADD COLUMN entry_hash CHAR(64) NULL;

CREATE UNIQUE INDEX uniq_audit_seq ON audit_events (seq);

-- APPEND-ONLY (defense in depth): the consumer only ever INSERTs, never updates/deletes. For a
-- hard, DB-enforced guarantee run these as an admin (needs the TRIGGER privilege), so not even the
-- app's audit_user can rewrite history:
--   CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
--     FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';
--   CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events
--     FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';
