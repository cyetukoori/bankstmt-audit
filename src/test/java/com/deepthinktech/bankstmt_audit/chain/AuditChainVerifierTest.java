package com.deepthinktech.bankstmt_audit.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import com.deepthinktech.bankstmt_audit.repo.AuditEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class AuditChainVerifierTest {

    private AuditEventRepository repo;
    private ChainMetrics metrics;
    private AuditVerifyProperties props;
    private AuditChainVerifier verifier;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        metrics = mock(ChainMetrics.class);
        props = new AuditVerifyProperties();
        props.setPageSize(2); // force several pages
        verifier = new AuditChainVerifier(repo, props, metrics);
    }

    /** Builds a correctly sealed chain of n rows. */
    private static List<AuditEvent> chain(int n) {
        List<AuditEvent> rows = new ArrayList<>();
        String prev = AuditChain.GENESIS;
        for (int i = 1; i <= n; i++) {
            AuditEvent e = new AuditEvent();
            e.setId("00000000-0000-0000-0000-00000000000" + i);
            e.setAction("GET /api/x");
            e.setDetails("status=200");
            e.setOccurredAt(OffsetDateTime.of(2026, 11, 22, 10, 0, i, 123_456_000, ZoneOffset.UTC));
            e.setSeq((long) i);
            e.setPrevHash(prev);
            e.setEntryHash(AuditChain.hash(prev, e));
            prev = e.getEntryHash();
            rows.add(e);
        }
        return rows;
    }

    private void serve(List<AuditEvent> rows) {
        when(repo.findBySeqGreaterThanOrderBySeqAsc(anyLong(), any(Pageable.class))).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            Pageable p = inv.getArgument(1);
            return rows.stream().filter(r -> r.getSeq() > after).limit(p.getPageSize()).toList();
        });
    }

    @Test
    void intactChainHasNoFindings() {
        serve(chain(5));
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.intact()).isTrue();
        assertThat(r.checked()).isEqualTo(5);
    }

    @Test
    void editedRowIsAContentBreak() {
        List<AuditEvent> rows = chain(5);
        rows.get(2).setDetails("status=403"); // someone rewrote history
        serve(rows);
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.findings()).extracting(AuditChainVerifier.Finding::kind).containsExactly("content");
        assertThat(r.findings().get(0).seq()).isEqualTo(3);
    }

    @Test
    void deletedRowIsAGapAndALinkBreak() {
        List<AuditEvent> rows = chain(5);
        rows.remove(2);
        serve(rows);
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.findings()).extracting(AuditChainVerifier.Finding::kind).containsExactly("gap", "link");
        assertThat(r.findings().get(0).seq()).isEqualTo(4);
    }

    @Test
    void resealedRowBreaksTheNextLink() {
        List<AuditEvent> rows = chain(4);
        AuditEvent second = rows.get(1);
        second.setDetails("status=500");
        second.setEntryHash(AuditChain.hash(second.getPrevHash(), second)); // attacker re-hashes the row
        serve(rows);
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.findings()).extracting(AuditChainVerifier.Finding::seq).containsExactly(3L);
        assertThat(r.findings()).extracting(AuditChainVerifier.Finding::kind).containsExactly("link");
    }

    @Test
    void contentCheckCanSkipLegacyRows() {
        List<AuditEvent> rows = chain(3);
        rows.get(0).setEntryHash("f".repeat(64)); // legacy row whose stored hash no longer recomputes
        rows.get(1).setPrevHash("f".repeat(64));
        rows.get(1).setEntryHash(AuditChain.hash(rows.get(1).getPrevHash(), rows.get(1)));
        rows.get(2).setPrevHash(rows.get(1).getEntryHash());
        rows.get(2).setEntryHash(AuditChain.hash(rows.get(2).getPrevHash(), rows.get(2)));
        props.setContentCheckFromSeq(2);
        serve(rows);
        assertThat(verifier.verify().intact()).isTrue();
    }

    @Test
    void scheduledRunPublishesTheResult() {
        serve(chain(3));
        verifier.scheduledVerify();
        verify(metrics).publish(3L, 0L);
    }

    @Test
    void aVerifierThatCannotRunReportsABreak() {
        when(repo.findBySeqGreaterThanOrderBySeqAsc(anyLong(), any(Pageable.class)))
            .thenThrow(new IllegalStateException("db down"));
        verifier.scheduledVerify();
        verify(metrics).publish(eq(0L), eq(1L));
    }

    @Test
    void canonicalTimeIsUtcMicros() {
        OffsetDateTime t = OffsetDateTime.of(2026, 11, 22, 12, 0, 0, 123_456_789, ZoneOffset.ofHours(2));
        assertThat(AuditChain.canonicalTime(t)).isEqualTo("2026-11-22T10:00:00.123456Z");
    }
}
