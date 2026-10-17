package com.itiarz.platform;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.itiarz.tenant.TenantStatus;

/** Request/response shapes of {@code /api/platform/tenants}. Responses never carry case data. */
public final class PlatformDtos {
    /** Keycloak organization alias rules (spec §6.3): 3–63 chars, lower-case letters, digits, inner hyphens. */
    public static final String SLUG_REGEX = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$";
    public static final String SLUG_MESSAGE = "Yalnızca küçük harf, rakam ve tire kullanın (3–63 karakter).";
    private static final String EMAIL_MESSAGE = "Geçerli bir e-posta adresi girin.";

    private PlatformDtos() { }

    public record CreateTenantRequest(
        @NotBlank(message = "Şirket adını girin.")
        @Size(max = 200, message = "Şirket adı en fazla 200 karakter olabilir.") String name,
        @NotNull(message = SLUG_MESSAGE) @Pattern(regexp = SLUG_REGEX, message = SLUG_MESSAGE) String slug,
        @NotBlank(message = EMAIL_MESSAGE) @Email(message = EMAIL_MESSAGE)
        @Size(max = 320, message = EMAIL_MESSAGE) String adminEmail
    ) { }

    public record InviteAdminRequest(
        @NotBlank(message = EMAIL_MESSAGE) @Email(message = EMAIL_MESSAGE)
        @Size(max = 320, message = EMAIL_MESSAGE) String email
    ) { }

    /** {@code memberCount}: INVITED + ACTIVE memberships. */
    public record TenantView(UUID id, String slug, String name, TenantStatus status, long memberCount,
                             Instant createdAt) { }

    /**
     * Result of tenant creation. {@code inviteSent=false}: the tenant exists but the first admin's
     * invitation e-mail failed; it can be re-sent via {@code POST /api/platform/tenants/{id}/invitations}.
     */
    public record TenantCreatedView(UUID id, String slug, String name, TenantStatus status, Instant createdAt,
                                    String adminEmail, boolean inviteSent) { }
}
