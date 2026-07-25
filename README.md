# bankstmt-audit

Standalone **audit ingestion microservice** for bankstmt. It is the only writer of the audit
database. Audit events are produced by `bankstmt-server` and delivered asynchronously so that
recording an audit trail can never slow down or fail a user request.

## Architecture

```
bankstmt-server ──publish──▶ SQS  ──consume──▶ bankstmt-audit ──insert──▶ MySQL (MySQLAuditDb)
   (SqsAuditPublisher)      main queue        (this service)            audit_events table

                 on repeated insert failure ──▶ SQS dead-letter queue ──▶ CloudWatch alarm
```

- **`bankstmt-server`** fire-and-forgets a JSON `AuditMessage` to the main SQS queue for every
  audited request. It never touches the audit DB.
- **This service** long-polls the queue (`AuditSqsConsumer`) and inserts each message into MySQL
  (`AuditInsertService`), then deletes it from the queue.
- Runs as a **Docker container on Elastic Beanstalk**.

## No message loss (the core guarantee)

- A message is deleted from SQS **only after** its row is durably committed. If the DB is down,
  the schema is wrong, or the payload is bad, the insert throws and the message is **kept** — it
  becomes visible again after the visibility timeout and is retried.
- After `maxReceiveCount` failed attempts the queue's redrive policy moves the message to the
  **dead-letter queue**, where it is retained (SQS max 14 days) until someone diagnoses the
  problem and redrives it. A CloudWatch alarm fires while the DLQ is non-empty.
- Inserts are **idempotent**: the row primary key is the producer-supplied `eventId`, so SQS
  at-least-once redelivery can never create duplicate rows.
- Poison messages (unparseable JSON / missing `eventId`) are also left on the queue and flow to
  the DLQ rather than being discarded.

> SQS caps retention at **14 days**; that is the ceiling on "hold until manual intervention".
> The DLQ + alarm design surfaces stuck messages well inside that window.

## Configuration (environment variables)

| Var | Purpose | Default |
|-----|---------|---------|
| `AUDIT_DB_URL` | JDBC URL of the MySQL audit DB | `jdbc:mysql://172.31.17.51:3306/bankstmt_audit?...` |
| `AUDIT_DB_USER` / `AUDIT_DB_PASSWORD` | DB credentials | `audit_user` / — |
| `AUDIT_QUEUE_URL` | main SQS queue URL | — (required) |
| `AUDIT_INGEST_ENABLED` | master switch for the consumer | `true` |
| `AUDIT_INGEST_CONCURRENCY` | parallel poll workers | `2` |
| `AUDIT_INGEST_VISIBILITY_SECONDS` | processing visibility timeout | `60` |

AWS credentials come from the default provider chain (the Elastic Beanstalk instance role) — no
static keys in config.

## Build & run

```bash
./gradlew bootJar          # build the fat jar (JDK 25)
docker build -t bankstmt-audit .
```

The `Dockerfile` is self-contained (builds the jar in a JDK-25 stage, runs it on a JRE stage),
so Elastic Beanstalk's Docker platform builds and runs it with no extra tooling.
