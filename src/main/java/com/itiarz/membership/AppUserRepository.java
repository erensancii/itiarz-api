package com.itiarz.membership;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByKeycloakUserId(String keycloakUserId);

    /** Race-safe JIT insert: concurrent first requests for one user never violate the unique key. */
    @Modifying
    @Query(value = """
            INSERT INTO app_users (id, keycloak_user_id, email, display_name, created_at, last_login_at)
            VALUES (:id, :keycloakUserId, :email, :displayName, now(), now())
            ON CONFLICT (keycloak_user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("keycloakUserId") String keycloakUserId,
                       @Param("email") String email, @Param("displayName") String displayName);

    /**
     * Finds or creates the user for a token subject and syncs e-mail/name (null = claim absent, keep
     * stored value; a new user without an e-mail claim gets ''). Call inside a transaction.
     */
    default AppUser provision(String keycloakUserId, String email, String displayName) {
        Optional<AppUser> existing = findByKeycloakUserId(keycloakUserId);
        if (existing.isPresent()) {
            existing.get().refresh(email, displayName, Instant.now());
            return existing.get();
        }
        insertIfAbsent(UUID.randomUUID(), keycloakUserId, email == null ? "" : email, displayName);
        return findByKeycloakUserId(keycloakUserId).orElseThrow();
    }
}
