package com.itiarz.membership;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.itiarz.security.TenantRole;

/** Request/response shapes of {@code /api/members}. */
public final class MemberDtos {
    private MemberDtos() { }

    public record InviteRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull TenantRole role
    ) { }

    public record RoleChangeRequest(@NotNull TenantRole role) { }

    /**
     * {@code userId}: the linked app user (what case assignment takes), null while the invitation is pending.
     * {@code email}: the linked user's current e-mail once the invitation is accepted, else the invited
     * address; {@code displayName} is null until then.
     */
    public record MemberView(UUID id, UUID userId, String email, String displayName, TenantRole role,
                             MembershipStatus status) { }
}
