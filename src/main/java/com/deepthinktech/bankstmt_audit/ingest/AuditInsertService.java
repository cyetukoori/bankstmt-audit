package com.deepthinktech.bankstmt_audit.ingest;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import com.deepthinktech.bankstmt_audit.repo.AuditEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a single {@link AuditMessage} to the audit database.
 *
 * <p>Insertion is idempotent: the row primary key is the producer-supplied
 * {@code eventId}, so a redelivered SQS message (SQS is at-least-once) collides on the
 * primary key. A collision is treated as success — the event is already recorded — so the
 * caller safely deletes the message. Any other failure propagates so the caller leaves the
 * message on the queue for retry.
 */
@Service
public class AuditInsertService {

    private static final Logger log = LoggerFactory.getLogger(AuditInsertService.class);

    private final AuditEventRepository repository;

    public AuditInsertService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts the event, or no-ops if a row with the same id already exists.
     *
     * @return {@code true} if a new row was written, {@code false} if it was already present
     * @throws RuntimeException if the insert fails for any reason other than a duplicate key
     *                          (the message must then stay on the queue)
     */
    @Transactional
    public boolean insert(AuditMessage message) {
        String id = message.eventId();
        if (id == null || id.isBlank()) {
            // A message with no idempotency key can never be safely deduplicated. Reject it
            // hard so it is NOT deleted — it lands on the DLQ for a human to inspect rather
            // than being silently written as a random-id row on every redelivery.
            throw new IllegalArgumentException("audit message is missing eventId");
        }
        if (repository.existsById(id)) {
            log.debug("Audit event {} already present — treating as inserted (idempotent)", id);
            return false;
        }
        AuditEvent event = toEntity(message);
        seal(event);
        // A save failure here — a duplicate id (race with another worker) OR a seq collision
        // (two workers racing for the same chain position) — is NOT swallowed: it propagates so the
        // consumer leaves the message on the queue. SQS redelivers it; the existsById check above
        // then short-circuits a true duplicate, and a seq collision re-seals at the next position.
        repository.save(event);
        return true;
    }

    /**
     * Assigns this event's chain position and hash from the current tail of the chain:
     * {@code seq = last.seq + 1}, {@code prev_hash = last.entry_hash} (genesis = 64 zeros), and
     * {@code entry_hash = SHA-256(prev_hash + '\n' + canonical(event))}.
     */
    private void seal(AuditEvent event) {
        AuditEvent last = repository.findTopByOrderBySeqDesc();
        long seq = (last != null && last.getSeq() != null) ? last.getSeq() + 1 : 1L;
        String prev = (last != null && last.getEntryHash() != null) ? last.getEntryHash() : GENESIS;
        event.setSeq(seq);
        event.setPrevHash(prev);
        event.setEntryHash(hash(prev, event));
    }

    private static final String GENESIS = "0".repeat(64);
    private static final String SEP = "\u001f"; // control char, cannot appear in values

    private static String hash(String prev, AuditEvent e) {
        String canonical = String.join(SEP,
            nz(e.getId()), nz(e.getOccurredAt() == null ? null : e.getOccurredAt().toString()),
            nz(e.getTenantId()), nz(e.getUserId()), nz(e.getAction()), nz(e.getSubjectType()),
            nz(e.getSubjectId()), nz(e.getDetails()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((prev + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private AuditEvent toEntity(AuditMessage m) {
        AuditEvent e = new AuditEvent();
        e.setId(m.eventId());
        e.setTenantId(m.tenantId());
        e.setUserId(m.userId());
        e.setAction(m.action());
        e.setSubjectType(m.subjectType());
        e.setSubjectId(m.subjectId());
        e.setIpAddress(m.ipAddress());
        e.setUserAgent(m.userAgent());
        e.setRequestId(m.requestId());
        e.setDetails(m.details());
        e.setOccurredAt(parseOccurredAt(m.occurredAt()));
        e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return e;
    }

    private OffsetDateTime parseOccurredAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            // A malformed timestamp must not fail the whole insert — the event still matters.
            log.warn("Unparseable occurredAt '{}' — storing null", value);
            return null;
        }
    }
}
