package com.deepthinktech.bankstmt_audit.chain;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import com.deepthinktech.bankstmt_audit.repo.AuditEventRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly tamper check of the whole audit trail (SOC 2 CC7.2 / CC4.1). Walks every sealed row in
 * chain order and checks, for each one:
 * <ul>
 *   <li><b>gap</b> — {@code seq} is contiguous (a deleted row leaves a hole);</li>
 *   <li><b>link</b> — {@code prev_hash} equals the previous row's {@code entry_hash} (a reordered
 *       or re-sealed row breaks the link);</li>
 *   <li><b>content</b> — {@code entry_hash} recomputed from the row's columns matches (an edited
 *       row no longer hashes to its stored value).</li>
 * </ul>
 * Any finding is logged at ERROR with the stable marker {@code AUDIT_CHAIN_BROKEN} and published as
 * the {@code BankStmt/Audit ChainBreaks} metric, which the CloudWatch alarm in the README watches.
 * Read-only: it never repairs anything — a break is evidence and must be investigated as an
 * incident. Rows written before the V2 migration (no {@code seq}) are outside the chain.
 */
@Component
public class AuditChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerifier.class);
    /** Findings are capped in memory/logs; the count is always exact. */
    private static final int MAX_REPORTED = 50;

    private final AuditEventRepository repository;
    private final AuditVerifyProperties props;
    private final ChainMetrics metrics;

    public AuditChainVerifier(AuditEventRepository repository, AuditVerifyProperties props, ChainMetrics metrics) {
        this.repository = repository;
        this.props = props;
        this.metrics = metrics;
    }

    public record Finding(long seq, String id, String kind) {
    }

    public record Result(long checked, long breaks, List<Finding> findings) {
        public boolean intact() {
            return breaks == 0;
        }
    }

    @Scheduled(cron = "${audit.verify.cron:0 30 3 * * *}", zone = "UTC")
    public void scheduledVerify() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            Result r = verify();
            metrics.publish(r.checked(), r.breaks());
        } catch (RuntimeException ex) {
            // A verifier that cannot run is itself a finding: report it as a break so the alarm fires.
            log.error("AUDIT_CHAIN_BROKEN verification could not complete: {}", ex.toString(), ex);
            metrics.publish(0, 1);
        }
    }

    public Result verify() {
        long checked = 0;
        long breaks = 0;
        List<Finding> findings = new ArrayList<>();
        AuditEvent prev = null;
        long cursor = 0;
        int pageSize = Math.max(1, props.getPageSize());
        while (true) {
            List<AuditEvent> page = repository.findBySeqGreaterThanOrderBySeqAsc(cursor, PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (AuditEvent row : page) {
                long seq = row.getSeq();
                List<String> kinds = new ArrayList<>(3);
                if (prev == null) {
                    // The first sealed row must be the genesis row.
                    if (seq != 1L) {
                        kinds.add("gap");
                    }
                    if (seq == 1L && !AuditChain.GENESIS.equals(row.getPrevHash())) {
                        kinds.add("link");
                    }
                } else {
                    if (seq != prev.getSeq() + 1) {
                        kinds.add("gap");
                    }
                    if (!Objects.equals(row.getPrevHash(), prev.getEntryHash())) {
                        kinds.add("link");
                    }
                }
                if (seq >= props.getContentCheckFromSeq()
                        && !Objects.equals(row.getEntryHash(), AuditChain.hash(nz(row.getPrevHash()), row))) {
                    kinds.add("content");
                }
                for (String kind : kinds) {
                    breaks++;
                    if (findings.size() < MAX_REPORTED) {
                        findings.add(new Finding(seq, row.getId(), kind));
                        log.error("AUDIT_CHAIN_BROKEN seq={} id={} kind={}", seq, row.getId(), kind);
                    }
                }
                checked++;
                prev = row;
                cursor = seq;
            }
        }
        if (breaks == 0) {
            log.info("Audit chain intact: {} sealed row(s) verified", checked);
        } else {
            log.error("AUDIT_CHAIN_BROKEN summary: {} finding(s) across {} row(s)", breaks, checked);
        }
        return new Result(checked, breaks, List.copyOf(findings));
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
