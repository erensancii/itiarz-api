package com.itiarz.membership;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Identity mirror of a Keycloak user, created just-in-time on first request. Shared across tenants. */
@Entity
@Table(name = "app_users")
public class AppUser {
    private static final Duration LOGIN_REFRESH = Duration.ofHours(1);

    @Id
    private UUID id;

    @Column(name = "keycloak_user_id", nullable = false, unique = true, length = 80)
    private String keycloakUserId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AppUser() {
    }

    public UUID getId() { return id; }
    public String getKeycloakUserId() { return keycloakUserId; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    /**
     * Syncs identity fields from the token; a missing (null) claim keeps the stored value.
     * last_login_at is refreshed at most hourly (no write per request).
     */
    void refresh(String email, String displayName, Instant now) {
        if (email != null) {
            this.email = email;
        }
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (lastLoginAt == null || lastLoginAt.isBefore(now.minus(LOGIN_REFRESH))) {
            lastLoginAt = now;
        }
    }
}
