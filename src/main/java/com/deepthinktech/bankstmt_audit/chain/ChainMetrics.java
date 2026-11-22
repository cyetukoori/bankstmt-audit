package com.deepthinktech.bankstmt_audit.chain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

/**
 * Publishes the verifier's result as CloudWatch metrics {@code BankStmt/Audit ChainBreaks} and
 * {@code ChainRowsVerified}. Fail-safe: a CloudWatch error is logged, never thrown — the ERROR log
 * line from the verifier still records the finding.
 */
public class ChainMetrics {

    static final String NAMESPACE = "BankStmt/Audit";
    private static final Logger log = LoggerFactory.getLogger(ChainMetrics.class);

    private final CloudWatchClient client; // null = metrics disabled

    public ChainMetrics(CloudWatchClient client) {
        this.client = client;
    }

    public void publish(long rowsVerified, long breaks) {
        if (client == null) {
            return;
        }
        try {
            client.putMetricData(PutMetricDataRequest.builder()
                .namespace(NAMESPACE)
                .metricData(
                    MetricDatum.builder().metricName("ChainBreaks").value((double) breaks).unit(StandardUnit.COUNT).build(),
                    MetricDatum.builder().metricName("ChainRowsVerified").value((double) rowsVerified).unit(StandardUnit.COUNT).build())
                .build());
        } catch (RuntimeException ex) {
            log.warn("Could not publish audit chain metrics: {}", ex.toString());
        }
    }
}
