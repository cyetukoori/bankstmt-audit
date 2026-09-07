package com.deepthinktech.bankstmt_audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

/**
 * The bootstrap entry point delegates to SpringApplication.run. Verified with a static mock so
 * no real context (and no MySQL/SQS) is started — that belongs to an integration test.
 */
class AuditServiceApplicationTest {

    @Test
    void mainDelegatesToSpringApplicationRun() {
        String[] args = {"--server.port=0"};
        try (MockedStatic<SpringApplication> springApp = mockStatic(SpringApplication.class)) {
            AuditServiceApplication.main(args);
            springApp.verify(() -> SpringApplication.run(eq(AuditServiceApplication.class), any(String[].class)), times(1));
        }
    }
}
