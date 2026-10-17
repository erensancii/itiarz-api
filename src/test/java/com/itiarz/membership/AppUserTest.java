package com.itiarz.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    void missingEmailOrNameClaimKeepsStoredValues() {
        AppUser user = new AppUser();
        user.refresh("ayse@example.com", "Ayşe", Instant.now());

        user.refresh(null, null, Instant.now());

        assertThat(user.getEmail()).isEqualTo("ayse@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Ayşe");
    }
}
