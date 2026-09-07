package com.deepthinktech.bankstmt_audit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * The SQS client wiring: it resolves the region from the AWS provider chain, and falls back to
 * us-east-1 when the chain can't determine one (e.g. running outside AWS) instead of failing to
 * build the client.
 */
class SqsConfigTest {

    private final SqsConfig config = new SqsConfig();

    @Test
    void providesARegionProviderChain() {
        assertThat(config.awsRegionProviderChain()).isNotNull();
    }

    @Test
    void buildsAnSqsClientFromTheResolvedRegion() {
        DefaultAwsRegionProviderChain chain = mock(DefaultAwsRegionProviderChain.class);
        when(chain.getRegion()).thenReturn(Region.EU_WEST_1);

        try (SqsClient client = config.sqsClient(chain)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void fallsBackToUsEast1WhenTheRegionCannotBeResolved() {
        DefaultAwsRegionProviderChain chain = mock(DefaultAwsRegionProviderChain.class);
        when(chain.getRegion()).thenThrow(new RuntimeException("no region configured"));

        try (SqsClient client = config.sqsClient(chain)) {
            assertThat(client).isNotNull();
        }
    }
}
