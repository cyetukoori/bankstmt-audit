package com.deepthinktech.bankstmt_audit.repo;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    /** The last sealed row (highest chain position), or null when the chain is empty. */
    AuditEvent findTopByOrderBySeqDesc();
}
