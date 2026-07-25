package com.deepthinktech.bankstmt_audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Audit ingestion microservice.
 *
 * <p>Subscribes to the audit SQS queue, and for every message inserts one row into the
 * MySQL audit database. A message is deleted from the queue <em>only</em> after its row is
 * durably committed, so a database outage never loses events — they stay on the queue (and
 * eventually the DLQ) until the problem is fixed. See {@code ingest.AuditSqsConsumer}.
 */
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
