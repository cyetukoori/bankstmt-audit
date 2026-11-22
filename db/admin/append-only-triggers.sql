-- Append-only enforcement for the audit trail (SOC 2 CC7.2). Run ONCE as a MySQL admin
-- (needs the TRIGGER privilege; on RDS with binary logging also set the parameter group's
-- log_bin_trust_function_creators=1, or run as the master user). Not a Flyway migration on
-- purpose: the service's own audit_user must NOT hold TRIGGER, or it could drop these.
--
--   mysql -h <audit-db-host> -u <admin> -p --ssl-mode=REQUIRED bankstmt_audit < db/admin/append-only-triggers.sql
--
-- Afterwards, remove UPDATE/DELETE/ALTER/DROP/TRIGGER from audit_user so the service account can
-- only INSERT and SELECT (the consumer never updates or deletes):
--   REVOKE UPDATE, DELETE, ALTER, DROP, TRIGGER ON bankstmt_audit.* FROM 'audit_user'@'%';
--   GRANT SELECT, INSERT ON bankstmt_audit.audit_events TO 'audit_user'@'%';
-- (Flyway still needs CREATE/ALTER for future migrations: grant them for the migration window,
--  or run migrations as the admin user.)
--
-- Verify: an UPDATE or DELETE on audit_events now fails with "audit_events is append-only".

DROP TRIGGER IF EXISTS audit_events_no_update;
DROP TRIGGER IF EXISTS audit_events_no_delete;

CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';

CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';
