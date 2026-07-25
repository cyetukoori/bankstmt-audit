package com.deepthinktech.bankstmt_audit.ingest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Wires the AWS SQS client. Credentials come from the default provider chain, which on
 * Elastic Beanstalk resolves to the EC2 instance role — no static keys in config.
 */
@Configuration
@EnableConfigurationProperties(AuditIngestProperties.class)
public class SqsConfig {

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain regionChain) {
        Region region;
        try {
            region = regionChain.getRegion();
        } catch (RuntimeException e) {
            region = Region.US_EAST_1;
        }
        return SqsClient.builder()
            .region(region)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean
    public software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain awsRegionProviderChain() {
        return new software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain();
    }
}
