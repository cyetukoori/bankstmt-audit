package com.deepthinktech.bankstmt_audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseTlsGuardTest {

    @Test
    void acceptsRequiredAndVerifyModes() {
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h:3306/db?sslMode=REQUIRED")).isTrue();
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h/db?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/x")).isTrue();
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h/db?useSSL=true")).isTrue();
    }

    @Test
    void rejectsPlaintextOrOptionalTls() {
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h/db?useSSL=false&allowPublicKeyRetrieval=true")).isFalse();
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h/db?sslMode=PREFERRED")).isFalse();
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h/db?sslMode=DISABLED")).isFalse();
        assertThat(DatabaseTlsGuard.isEncrypted("jdbc:mysql://h/db")).isFalse();
    }

    @Test
    void failsStartupWhenRequired() {
        assertThatThrownBy(() -> DatabaseTlsGuard.check("jdbc:mysql://h/db?useSSL=false", true))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("sslMode=REQUIRED");
    }

    @Test
    void onlyWarnsWhenExplicitlyRelaxed() {
        assertThatCode(() -> DatabaseTlsGuard.check("jdbc:mysql://h/db?useSSL=false", false)).doesNotThrowAnyException();
    }

    @Test
    void ignoresNonMysqlUrls() {
        assertThatCode(() -> DatabaseTlsGuard.check("jdbc:h2:mem:test", true)).doesNotThrowAnyException();
        assertThatCode(() -> DatabaseTlsGuard.check("", true)).doesNotThrowAnyException();
    }
}
