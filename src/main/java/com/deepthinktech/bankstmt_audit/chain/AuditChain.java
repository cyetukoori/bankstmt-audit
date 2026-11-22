package com.deepthinktech.bankstmt_audit.chain;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * The hash-chain definition, shared by the writer ({@code AuditInsertService}, which seals rows)
 * and the reader ({@link AuditChainVerifier}, which re-checks them), so both always agree.
 *
 * <pre>entry_hash = SHA-256( prev_hash + '\n' + canonical(id, occurred_at, tenant, user, action,
 *                                                  subject_type, subject_id, details) )</pre>
 *
 * <p>{@code occurred_at} is canonicalised to UTC at microsecond precision — exactly what a MySQL
 * {@code DATETIME(6)} column stores — so a hash computed before the insert matches the one
 * recomputed from the row read back. For the UTC, microsecond timestamps the API publishes this
 * is byte-identical to the original definition, so rows sealed before it still verify.
 */
public final class AuditChain {

    /** prev_hash of the first sealed row. */
    public static final String GENESIS = "0".repeat(64);

    private static final String SEP = "\u001f"; // control char, cannot appear in values

    private AuditChain() {
    }

    public static String hash(String prev, AuditEvent e) {
        String canonical = String.join(SEP,
            nz(e.getId()), nz(canonicalTime(e.getOccurredAt())),
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

    static String canonicalTime(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
