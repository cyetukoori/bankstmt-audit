package com.deepthinktech.bankstmt_audit.chain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code audit.verify.*} — the nightly hash-chain verification. */
@ConfigurationProperties(prefix = "audit.verify")
public class AuditVerifyProperties {

    /** Run the scheduled verification. */
    private boolean enabled = true;

    /** Rows read per query. */
    private int pageSize = 1000;

    /**
     * Recompute entry hashes only from this seq on (gap/link checks always cover the whole chain).
     * Leave at 1; raise it only if rows sealed before the canonical-time fix turn out to carry
     * sub-microsecond timestamps, and record why.
     */
    private long contentCheckFromSeq = 1;

    /** Publish the result as CloudWatch metrics (namespace BankStmt/Audit). */
    private boolean cloudwatchEnabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public long getContentCheckFromSeq() { return contentCheckFromSeq; }
    public void setContentCheckFromSeq(long contentCheckFromSeq) { this.contentCheckFromSeq = contentCheckFromSeq; }
    public boolean isCloudwatchEnabled() { return cloudwatchEnabled; }
    public void setCloudwatchEnabled(boolean cloudwatchEnabled) { this.cloudwatchEnabled = cloudwatchEnabled; }
}
