package com.deepthinktech.bankstmt_audit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the SQS ingestion loop, bound from {@code audit.ingest.*}.
 *
 * @param queueUrl          full URL of the audit SQS queue (required to run the consumer)
 * @param enabled           master switch; when false the consumer never starts (used by tests)
 * @param concurrency       number of parallel long-poll workers
 * @param waitTimeSeconds   SQS long-poll wait (0-20); 20 minimises empty receives / cost
 * @param maxMessages       messages pulled per receive call (1-10)
 * @param visibilityTimeoutSeconds  how long a received message is hidden while we process it;
 *                          must exceed worst-case insert latency so a slow insert is not
 *                          redelivered and processed twice concurrently
 */
@ConfigurationProperties(prefix = "audit.ingest")
public record AuditIngestProperties(
    String queueUrl,
    boolean enabled,
    int concurrency,
    int waitTimeSeconds,
    int maxMessages,
    int visibilityTimeoutSeconds
) {
    public AuditIngestProperties {
        if (concurrency <= 0) concurrency = 2;
        if (waitTimeSeconds <= 0 || waitTimeSeconds > 20) waitTimeSeconds = 20;
        if (maxMessages <= 0 || maxMessages > 10) maxMessages = 10;
        if (visibilityTimeoutSeconds <= 0) visibilityTimeoutSeconds = 60;
    }
}
