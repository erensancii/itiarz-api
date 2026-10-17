package com.itiarz.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditServiceIpTest {

    @Test
    void keepsValidLiterals() {
        assertThat(AuditService.normalizeIp("203.0.113.7")).isEqualTo("203.0.113.7");
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
    }
}
