-- Audit event store for the bankstmt audit microservice.
-- UUID-valued columns are CHAR(36) for phpMyAdmin readability; `id` is the producer-supplied
-- event id, which makes SQS redelivery idempotent (duplicate id => duplicate-key, not a 2nd row).
CREATE TABLE IF NOT EXISTS audit_events (
    id           CHAR(36)      NOT NULL,
    tenant_id    CHAR(36)      NULL,
    user_id      CHAR(36)      NULL,
    action       VARCHAR(255)  NOT NULL,
    subject_type VARCHAR(255)  NULL,
    subject_id   CHAR(36)      NULL,
    ip_address   VARCHAR(64)   NULL,
    user_agent   VARCHAR(1000) NULL,
    request_id   VARCHAR(255)  NULL,
    details      VARCHAR(4000) NULL,
    occurred_at  DATETIME(6)   NULL,
    created_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_tenant_created (tenant_id, created_at),
    KEY idx_audit_tenant_user_created (tenant_id, user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
