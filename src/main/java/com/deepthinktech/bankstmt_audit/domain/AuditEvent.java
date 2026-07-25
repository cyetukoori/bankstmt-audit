package com.deepthinktech.bankstmt_audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One recorded audit event, mirroring the shape the monolith used to persist to Postgres
 * (see {@code bankstmt-server domain/audit/AuditEvent}) — now stored in the standalone
 * MySQL audit database.
 *
 * <p>The primary key {@link #id} is the publisher-supplied event id, which makes inserts
 * idempotent: a redelivered SQS message maps to the same primary key, so a duplicate is
 * detected on the unique constraint rather than producing a second row.
 *
 * <p>UUID-valued columns are stored as {@code CHAR(36)} strings for phpMyAdmin readability;
 * the ingestion layer keeps them as plain strings and never has to parse them.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "subject_type")
    private String subjectType;

    @Column(name = "subject_id", length = 36)
    private String subjectId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 1000)
    private String userAgent;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "details", length = 4000)
    private String details;

    /** When the event happened in the producer (monolith). May be null for legacy messages. */
    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    /** When this row was written to the audit DB. Set by the ingestion layer at insert time. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Strictly increasing chain position, assigned by the single-writer sealer. */
    @Column(name = "seq", updatable = false)
    private Long seq;

    /** Hash of the previous chained row (64 hex chars); genesis is 64 zeros. */
    @Column(name = "prev_hash", updatable = false, length = 64)
    private String prevHash;

    /** SHA-256 of {@code prev_hash + '\n' + canonical(this)} — breaks if any prior row is altered. */
    @Column(name = "entry_hash", updatable = false, length = 64)
    private String entryHash;
}
