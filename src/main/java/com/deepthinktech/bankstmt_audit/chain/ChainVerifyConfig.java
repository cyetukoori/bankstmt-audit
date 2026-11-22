package com.deepthinktech.bankstmt_audit.chain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuditVerifyProperties.class)
public class ChainVerifyConfig {

    @Bean
    ChainMetrics chainMetrics(AuditVerifyProperties props,
                              @Value("${AWS_REGION:us-east-1}") String region) {
        if (!props.isCloudwatchEnabled()) {
            return new ChainMetrics(null);
        }
        return new ChainMetrics(CloudWatchClient.builder().region(Region.of(region)).build());
    }
}
