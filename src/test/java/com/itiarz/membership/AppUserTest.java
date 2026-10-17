package com.itiarz.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    void missingEmailOrNameClaimKeepsStoredValues() {
        AppUser user = new AppUser();
        user.refresh("ayse@example.com", true, "Ayşe", Instant.now());

        user.refresh(null, true, null, Instant.now());

        assertThat(user.getEmail()).isEqualTo("ayse@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Ayşe");
    }

    @Test
    void verifiedEmailClaimUpdatesStoredEmail() {
        AppUser user = new AppUser();
        user.refresh("ayse@example.com", true, null, Instant.now());

        user.refresh("ayse.yeni@example.com", true, null, Instant.now());

        assertThat(user.getEmail()).isEqualTo("ayse.yeni@example.com");
    }

    @Test
    void unverifiedEmailClaimNeverOverwritesStoredEmail() {
        AppUser user = new AppUser();
        user.refresh("ayse@example.com", true, null, Instant.now());

        user.refresh("saldirgan@example.com", false, null, Instant.now());

        assertThat(user.getEmail()).isEqualTo("ayse@example.com");
    }

    @Test
    void unverifiedEmailClaimMayFillAnEmptyStoredEmail() {
        AppUser user = new AppUser();

        user.refresh("ilk@example.com", false, null, Instant.now());

        assertThat(user.getEmail()).isEqualTo("ilk@example.com");
    }
}
