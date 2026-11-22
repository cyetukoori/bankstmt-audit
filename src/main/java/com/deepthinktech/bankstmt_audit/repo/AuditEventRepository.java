package com.deepthinktech.bankstmt_audit.repo;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    /** The last sealed row (highest chain position), or null when the chain is empty. */
    AuditEvent findTopByOrderBySeqDesc();

    /** One page of the sealed chain after {@code seq}, in chain order (for the verifier). */
    List<AuditEvent> findBySeqGreaterThanOrderBySeqAsc(long seq, Pageable page);
}
