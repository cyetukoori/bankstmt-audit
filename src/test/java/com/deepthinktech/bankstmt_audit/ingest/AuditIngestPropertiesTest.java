package com.deepthinktech.bankstmt_audit.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The compact constructor clamps out-of-range tuning values to safe defaults so a typo in
 * config can't produce a 0-thread consumer or an illegal SQS long-poll wait.
 */
class AuditIngestPropertiesTest {

    @Test
    void keepsValidValues() {
        AuditIngestProperties p = new AuditIngestProperties("q", true, 4, 10, 5, 90);

        assertThat(p.queueUrl()).isEqualTo("q");
        assertThat(p.enabled()).isTrue();
        assertThat(p.concurrency()).isEqualTo(4);
        assertThat(p.waitTimeSeconds()).isEqualTo(10);
        assertThat(p.maxMessages()).isEqualTo(5);
        assertThat(p.visibilityTimeoutSeconds()).isEqualTo(90);
    }

    @Test
    void defaultsConcurrencyWhenNonPositive() {
        assertThat(new AuditIngestProperties("q", true, 0, 10, 5, 90).concurrency()).isEqualTo(2);
        assertThat(new AuditIngestProperties("q", true, -3, 10, 5, 90).concurrency()).isEqualTo(2);
    }

    @Test
    void clampsWaitTimeToTheValidLongPollRange() {
        assertThat(new AuditIngestProperties("q", true, 1, 0, 5, 90).waitTimeSeconds()).isEqualTo(20);
        assertThat(new AuditIngestProperties("q", true, 1, 21, 5, 90).waitTimeSeconds()).isEqualTo(20);
        assertThat(new AuditIngestProperties("q", true, 1, 20, 5, 90).waitTimeSeconds()).isEqualTo(20);
    }

    @Test
    void clampsMaxMessagesToTheSqsBatchLimit() {
        assertThat(new AuditIngestProperties("q", true, 1, 10, 0, 90).maxMessages()).isEqualTo(10);
        assertThat(new AuditIngestProperties("q", true, 1, 10, 11, 90).maxMessages()).isEqualTo(10);
    }

    @Test
    void defaultsVisibilityTimeoutWhenNonPositive() {
        assertThat(new AuditIngestProperties("q", true, 1, 10, 5, 0).visibilityTimeoutSeconds()).isEqualTo(60);
        assertThat(new AuditIngestProperties("q", true, 1, 10, 5, -1).visibilityTimeoutSeconds()).isEqualTo(60);
    }
}
