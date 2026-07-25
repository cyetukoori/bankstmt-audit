package com.deepthinktech.bankstmt_audit.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polls the audit SQS queue and inserts every message into the audit database.
 *
 * <p><b>No message loss.</b> The loop deletes a message from the queue only after its row is
 * committed (or found already committed — idempotent). If the insert throws — DB down, schema
 * mismatch, bad payload — the message is <em>not</em> deleted. After its visibility timeout it
 * becomes visible again and is retried. Once it has failed {@code maxReceiveCount} times the
 * queue's redrive policy moves it to the dead-letter queue, where it is retained (up to SQS's
 * 14-day maximum) until someone fixes the cause and redrives it. Nothing is ever dropped by
 * this service.
 *
 * <p>Poison messages (unparseable JSON / missing eventId) are also left on the queue so they
 * flow to the DLQ rather than being discarded — a malformed audit event is a signal, not noise.
 */
@Component
public class AuditSqsConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditSqsConsumer.class);

    private final SqsClient sqs;
    // Constructed directly rather than injected: Spring Boot 4 defaults to Jackson 3, so there
    // is no auto-configured Jackson-2 ObjectMapper bean. The payload is all strings, so a plain
    // mapper with no extra modules is sufficient.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditInsertService insertService;
    private final AuditIngestProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pollers;

    public AuditSqsConsumer(SqsClient sqs, AuditInsertService insertService, AuditIngestProperties props) {
        this.sqs = sqs;
        this.insertService = insertService;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.enabled()) {
            log.warn("Audit SQS consumer disabled (audit.ingest.enabled=false) — not polling.");
            return;
        }
        if (props.queueUrl() == null || props.queueUrl().isBlank()) {
            log.error("Audit SQS consumer enabled but audit.ingest.queue-url is not set — not polling.");
            return;
        }
        running.set(true);
        pollers = Executors.newFixedThreadPool(props.concurrency(), r -> {
            Thread t = new Thread(r, "audit-sqs-poller");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < props.concurrency(); i++) {
            pollers.submit(this::pollLoop);
        }
        log.info("Audit SQS consumer started: {} worker(s) polling {}", props.concurrency(), props.queueUrl());
    }

    private void pollLoop() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
            .queueUrl(props.queueUrl())
            .maxNumberOfMessages(props.maxMessages())
            .waitTimeSeconds(props.waitTimeSeconds())
            .visibilityTimeout(props.visibilityTimeoutSeconds())
            .build();

        while (running.get()) {
            List<Message> messages;
            try {
                messages = sqs.receiveMessage(request).messages();
            } catch (RuntimeException e) {
                if (!running.get()) break;
                // Transient SQS/network error — back off briefly and retry. Never exit the loop.
                log.warn("SQS receive failed, retrying shortly: {}", e.toString());
                sleep(2000);
                continue;
            }
            for (Message message : messages) {
                if (!running.get()) return;
                handle(message);
            }
        }
    }

    /** Processes one message; deletes it only on a durable insert (or idempotent duplicate). */
    private void handle(Message message) {
        AuditMessage payload;
        try {
            payload = objectMapper.readValue(message.body(), AuditMessage.class);
        } catch (Exception parseError) {
            // Poison message: leave it on the queue so it flows to the DLQ for inspection.
            log.error("Unparseable audit message {} left on queue for DLQ: {}",
                message.messageId(), parseError.toString());
            return;
        }
        try {
            boolean inserted = insertService.insert(payload);
            delete(message);
            if (inserted) {
                log.debug("Inserted audit event {} and deleted message {}",
                    payload.eventId(), message.messageId());
            }
        } catch (RuntimeException insertError) {
            // Insert failed (DB down, constraint, bad data). Do NOT delete — the message stays
            // on the queue, is retried after the visibility timeout, and ultimately goes to the
            // DLQ. This is the guarantee: an event that cannot be stored is never lost.
            log.error("Failed to insert audit event {} (message {} kept on queue): {}",
                payload.eventId(), message.messageId(), insertError.toString());
        }
    }

    private void delete(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(props.queueUrl())
            .receiptHandle(message.receiptHandle())
            .build());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollers != null) {
            pollers.shutdown();
            try {
                if (!pollers.awaitTermination(25, TimeUnit.SECONDS)) {
                    pollers.shutdownNow();
                }
            } catch (InterruptedException e) {
                pollers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
