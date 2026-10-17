package com.itiarz.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditServiceIpTest {

    @Test
    void keepsValidLiterals() {
        assertThat(AuditService.normalizeIp("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(AuditService.normalizeIp("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(AuditService.normalizeIp("::1")).isEqualTo("::1");
        assertThat(AuditService.normalizeIp("2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(AuditService.normalizeIp("::ffff:1.2.3.4")).isEqualTo("::ffff:1.2.3.4");
        assertThat(AuditService.normalizeIp("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void stripsZoneId() {
        assertThat(AuditService.normalizeIp("fe80::1%eth0")).isEqualTo("fe80::1");
        assertThat(AuditService.normalizeIp("fe80::1%2")).isEqualTo("fe80::1");
    }

    @Test
    void invalidOrBlankBecomesNull() {
        assertThat(AuditService.normalizeIp(null)).isNull();
        assertThat(AuditService.normalizeIp("")).isNull();
        assertThat(AuditService.normalizeIp("example.com")).isNull();
        assertThat(AuditService.normalizeIp("999.1.1.1")).isNull();
        assertThat(AuditService.normalizeIp("%eth0")).isNull();
        assertThat(AuditService.normalizeIp("::::")).isNull();
        assertThat(AuditService.normalizeIp("a:b")).isNull();
        assertThat(AuditService.normalizeIp("1.2.3")).isNull();
        assertThat(AuditService.normalizeIp("1.2.3.4.5")).isNull();
        assertThat(AuditService.normalizeIp("1:2:3:4:5:6:7:8:9")).isNull();
        assertThat(AuditService.normalizeIp("12345::1")).isNull();
    }
}
