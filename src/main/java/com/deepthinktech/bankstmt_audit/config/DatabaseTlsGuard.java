package com.deepthinktech.bankstmt_audit.config;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start against a MySQL audit database over an unencrypted connection (SOC 2 CC6.7:
 * data in transit). The audit trail holds IPs, user agents and a record of every customer action,
 * so it gets the same TLS requirement as the main RDS database.
 *
 * <p>Only {@code jdbc:mysql:} URLs are inspected. A URL is rejected when it turns TLS off
 * ({@code useSSL=false}, {@code sslMode=DISABLED}) or makes it optional ({@code sslMode=PREFERRED}),
 * and when it sets neither {@code sslMode=REQUIRED|VERIFY_CA|VERIFY_IDENTITY} nor {@code useSSL=true}.
 * {@code AUDIT_DB_REQUIRE_TLS=false} is the documented escape hatch for a local database only.
 */
@Component
public class DatabaseTlsGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTlsGuard.class);

    private final String url;
    private final boolean requireTls;

    public DatabaseTlsGuard(@Value("${spring.datasource.url:}") String url,
                            @Value("${audit.db.require-tls:true}") boolean requireTls) {
        this.url = url;
        this.requireTls = requireTls;
    }

    @Override
    public void afterPropertiesSet() {
        check(url, requireTls);
    }

    static void check(String url, boolean requireTls) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
            return;
        }
        if (isEncrypted(url)) {
            return;
        }
        String msg = "Audit DB connection is not TLS-encrypted. Set sslMode=REQUIRED (or VERIFY_CA / "
            + "VERIFY_IDENTITY) in AUDIT_DB_URL. Only for a local database: AUDIT_DB_REQUIRE_TLS=false.";
        if (requireTls) {
            throw new IllegalStateException(msg);
        }
        log.warn(msg);
    }

    static boolean isEncrypted(String url) {
        String u = url.toLowerCase(Locale.ROOT).replace(" ", "");
        if (u.contains("usessl=false") || u.contains("sslmode=disabled") || u.contains("sslmode=preferred")) {
            return false;
        }
        return u.contains("sslmode=required") || u.contains("sslmode=verify_ca")
            || u.contains("sslmode=verify_identity") || u.contains("usessl=true");
    }
}
