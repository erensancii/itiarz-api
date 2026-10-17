package com.itiarz.membership;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itiarz.audit.AuditActions;
import com.itiarz.audit.AuditService;
import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.common.FieldValidationException;
import com.itiarz.keycloak.KeycloakGateway;
import com.itiarz.membership.MemberDtos.MemberView;
import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;
import com.itiarz.security.TenantRole;
import com.itiarz.tenant.Tenant;
import com.itiarz.tenant.TenantRepository;

/**
 * Tenant team management (spec §6.4): invitations, role changes and removal, all audited.
 *
 * <p>Two entry points create an invitation:
 * <ul>
 *   <li>{@link #createInvitation}: database row + {@code member.invited} audit only, no Keycloak call.
 *       For callers that own the transaction and talk to Keycloak themselves, e.g. platform tenant
 *       creation (Task 9) calls it inside its own (platform-mode) transaction and sends the Keycloak
 *       invitation after commit.</li>
 *   <li>{@link #invite}: {@code createInvitation} followed by
 *       {@link KeycloakGateway#inviteToOrganization} as the <em>last</em> step of the same transaction,
 *       so a Keycloak failure rolls the row and the audit event back (502, nothing persisted).</li>
 * </ul>
 * E-mails are normalized with {@code trim().toLowerCase(Locale.ROOT)} (locale independent). Removal is
 * likewise ordered with the Keycloak call last, so a Keycloak failure leaves the membership untouched.
 * Membership ids are resolved within the caller's tenant only: removed or foreign ids are 404.
 */
@Service
public class MembershipService {
    private static final int EMAIL_MAX = 320;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final KeycloakGateway keycloak;
    private final AuditService audit;

    public MembershipService(MembershipRepository memberships, AppUserRepository users, TenantRepository tenants,
                             KeycloakGateway keycloak, AuditService audit) {
        this.memberships = memberships;
        this.users = users;
        this.tenants = tenants;
        this.keycloak = keycloak;
        this.audit = audit;
    }

    /** Non-removed members of the tenant, ordered by e-mail. */
    @Transactional(readOnly = true)
    public List<MemberView> list(UUID tenantId) {
        List<Membership> rows = memberships.findByTenantIdAndStatusNot(tenantId, MembershipStatus.REMOVED);
        Map<UUID, AppUser> userById = users.findAllById(
                        rows.stream().map(Membership::getUserId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return rows.stream()
                .map(m -> view(m, m.getUserId() == null ? null : userById.get(m.getUserId())))
                .sorted(Comparator.comparing(MemberView::email))
                .toList();
    }

    /**
     * Creates (or, for a REMOVED row, reactivates in place with the same id) an INVITED membership and
     * audits {@code member.invited}. No Keycloak call. An e-mail with a pending invitation or an active
     * membership in the tenant is 409 DUPLICATE_INVITE. Joins the caller's transaction, whose RLS
     * context must allow writing the tenant's memberships (tenant mode for that tenant, or platform mode).
     */
    @Transactional
    public Membership createInvitation(UUID tenantId, String email, TenantRole role, UUID invitedBy) {
        String normalized = normalizeEmail(email);
        if (role == null) {
            throw new FieldValidationException("role", "Rol seçilmelidir.");
        }
        if (memberships.existsLiveMemberWithUserEmail(tenantId, normalized)) {
            throw duplicateInvite();
        }
        Membership membership = memberships.findByTenantIdAndInvitedEmail(tenantId, normalized)
                .map(existing -> {
                    if (existing.getStatus() != MembershipStatus.REMOVED) {
                        throw duplicateInvite();
                    }
                    existing.reinvite(role, invitedBy);
                    return existing;
                })
                .orElseGet(() -> memberships.save(Membership.invitation(tenantId, normalized, role, invitedBy)));
        memberships.flush();
        auditFor(tenantId, AuditActions.MEMBER_INVITED, membership.getId(),
                details("email", normalized, "role", role.name()));
        return membership;
    }

    /**
     * {@link #createInvitation} plus the Keycloak invitation e-mail as the last step of the same
     * transaction; a Keycloak failure (502 / 500) rolls everything back.
     */
    @Transactional
    public Membership invite(UUID tenantId, String email, TenantRole role, UUID invitedBy) {
        Membership membership = createInvitation(tenantId, email, role, invitedBy);
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));
        keycloak.inviteToOrganization(tenant.getKeycloakOrgId(), membership.getInvitedEmail());
        return membership;
    }

    /**
     * Changes a member's role in the caller's tenant. Demoting the last active TENANT_ADMIN is 409
     * LAST_ADMIN; changing one's own role is 409 CONFLICT. Setting the current role is a no-op.
     */
    @Transactional
    public MemberView changeRole(UUID membershipId, TenantRole role) {
        RequestContext context = RequestContextHolder.get();
        Membership membership = findLive(membershipId, context.tenantId());
        if (membership.getRole() == role) {
            return view(membership);
        }
        if (membership.getRole() == TenantRole.TENANT_ADMIN) {
            requireAnotherActiveAdmin(membership);
        }
        if (context.userId().equals(membership.getUserId())) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                    "Kendi rolünüzü değiştiremezsiniz. Başka bir şirket yöneticisinden yardım isteyin.");
        }
        TenantRole previous = membership.getRole();
        membership.changeRole(role);
        memberships.flush();
        audit.record(AuditActions.MEMBER_ROLE_CHANGED, "membership", membership.getId().toString(),
                details("email", membership.getInvitedEmail(), "from", previous.name(), "to", role.name()));
        return view(membership);
    }

    /**
     * Removes a member (status REMOVED, row kept). Removing the last active TENANT_ADMIN is 409
     * LAST_ADMIN. A linked user is also removed from the Keycloak organization, as the last step so a
     * Keycloak failure rolls the removal back.
     */
    @Transactional
    public void remove(UUID membershipId) {
        RequestContext context = RequestContextHolder.get();
        Membership membership = findLive(membershipId, context.tenantId());
        if (membership.getRole() == TenantRole.TENANT_ADMIN) {
            requireAnotherActiveAdmin(membership);
        }
        membership.remove();
        memberships.flush();
        audit.record(AuditActions.MEMBER_REMOVED, "membership", membership.getId().toString(),
                details("email", membership.getInvitedEmail(), "role", membership.getRole().name()));
        if (membership.getUserId() != null) {
            String keycloakUserId = users.findById(membership.getUserId())
                    .map(AppUser::getKeycloakUserId).orElse(null);
            if (keycloakUserId != null && !keycloakUserId.isBlank()) {
                Tenant tenant = tenants.findById(context.tenantId()).orElseThrow();
                keycloak.removeOrganizationMember(tenant.getKeycloakOrgId(), keycloakUserId);
            }
        }
    }

    /** Response view; loads the linked user (if any) for its current e-mail and display name. */
    public MemberView view(Membership membership) {
        AppUser user = membership.getUserId() == null ? null
                : users.findById(membership.getUserId()).orElse(null);
        return view(membership, user);
    }

    static MemberView view(Membership membership, AppUser user) {
        String email = user != null && user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail() : membership.getInvitedEmail();
        return new MemberView(membership.getId(), email, user == null ? null : user.getDisplayName(),
                membership.getRole(), membership.getStatus());
    }

    /** Trims and lower-cases with {@code Locale.ROOT}; blank or malformed is 422 on {@code email}. */
    static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > EMAIL_MAX || !EMAIL.matcher(normalized).matches()) {
            throw new FieldValidationException("email", "Geçerli bir e-posta adresi girin.");
        }
        return normalized;
    }

    private Membership findLive(UUID membershipId, UUID tenantId) {
        // findById alone is not enough: the self-read policy exposes the caller's rows in other tenants
        return memberships.findById(membershipId)
                .filter(m -> m.getTenantId().equals(tenantId) && m.getStatus() != MembershipStatus.REMOVED)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                        "Üye bulunamadı. Listeyi yenileyip tekrar deneyin."));
    }

    /**
     * 409 LAST_ADMIN when {@code target} is an active TENANT_ADMIN and no other active one remains.
     * The admin rows are locked (FOR UPDATE) so concurrent demotions/removals cannot both pass.
     */
    private void requireAnotherActiveAdmin(Membership target) {
        List<Membership> admins = memberships.lockActiveByRole(target.getTenantId(), TenantRole.TENANT_ADMIN);
        boolean another = admins.stream().anyMatch(m -> !m.getId().equals(target.getId()));
        if (!another && target.getStatus() == MembershipStatus.ACTIVE) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.LAST_ADMIN,
                    "Şirketin son yöneticisi çıkarılamaz veya rolü düşürülemez. Önce başka bir üyeyi yönetici yapın.");
        }
    }

    /** Tenant mode: the event goes to the request's tenant (must match); platform mode: explicit tenant. */
    private void auditFor(UUID tenantId, String action, UUID membershipId, Map<String, Object> details) {
        if (RequestContextHolder.platformMode()) {
            audit.recordForTenant(tenantId, action, "membership", membershipId.toString(), details);
            return;
        }
        if (!tenantId.equals(RequestContextHolder.requireTenantId())) {
            throw new IllegalStateException("Invitation tenant differs from the request tenant");
        }
        audit.record(action, "membership", membershipId.toString(), details);
    }

    private static Map<String, Object> details(String... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static DomainException duplicateInvite() {
        return new DomainException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_INVITE,
                "Bu e-posta için zaten bekleyen bir davet veya etkin bir üyelik var.");
    }
}
