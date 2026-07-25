package com.deepthinktech.bankstmt_audit.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire shape of an audit event on the SQS queue, produced by the monolith's
 * {@code SqsAuditPublisher}. Kept deliberately permissive ({@link JsonIgnoreProperties})
 * so the producer can add fields without breaking older consumers.
 *
 * @param eventId    idempotency key / primary key of the audit row (required)
 * @param tenantId   owning tenant, may be null
 * @param userId     acting user, may be null
 * @param action     what happened, e.g. {@code "POST /api/statements"} (required)
 * @param subjectType coarse subject classification, may be null
 * @param subjectId  affected entity id, may be null
 * @param ipAddress  client IP, may be null
 * @param userAgent  client user-agent, may be null
 * @param requestId  correlation id, may be null
 * @param details    free-text/JSON detail, may be null
 * @param occurredAt when the event happened in the producer, ISO-8601 string, may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditMessage(
    String eventId,
    String tenantId,
    String userId,
    String action,
    String subjectType,
    String subjectId,
    String ipAddress,
    String userAgent,
    String requestId,
    String details,
    String occurredAt
) {
}
