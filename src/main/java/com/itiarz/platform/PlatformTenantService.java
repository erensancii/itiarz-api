package com.itiarz.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.itiarz.audit.AuditActions;
import com.itiarz.audit.AuditService;
import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.keycloak.KeycloakGateway;
import com.itiarz.membership.MemberDtos.MemberView;
import com.itiarz.membership.Membership;
import com.itiarz.membership.MembershipRepository;
import com.itiarz.membership.MembershipRepository.TenantMemberCount;
import com.itiarz.membership.MembershipService;
import com.itiarz.membership.MembershipStatus;
import com.itiarz.platform.PlatformDtos.CreateTenantRequest;
import com.itiarz.platform.PlatformDtos.TenantCreatedView;
import com.itiarz.platform.PlatformDtos.TenantView;
import com.itiarz.security.RequestContextHolder;
import com.itiarz.security.TenantRole;
import com.itiarz.tenant.Tenant;
import com.itiarz.tenant.TenantRepository;
import com.itiarz.tenant.TenantStatus;

/**
 * Platform admin operations on tenants (spec §6.3). Every database access runs in a transaction that
 * begins inside {@link RequestContextHolder#runAsPlatform} ({@code app.platform='on'}, no tenant id),
 * so case data stays invisible. Platform events (tenant.*) are audited with {@code tenant_id NULL},
 * target {@code tenant/<id>}.
 *
 * <p>Tenant creation: slug uniqueness (DB) → Keycloak organization (alias = slug) → one platform
 * transaction with the tenant row, the TENANT_ADMIN invitation and {@code tenant.created}; if that
 * transaction fails the organization is deleted again (compensation) and the original error is
 * rethrown → after commit the Keycloak invitation e-mail; its failure keeps the tenant, audits
 * {@code tenant.invite_failed} and answers {@code inviteSent=false} (re-send via the invitations endpoint).
 */
@Service
public class PlatformTenantService {
    private static final Logger log = LoggerFactory.getLogger(PlatformTenantService.class);
    private static final String TARGET = "tenant";

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final MembershipService membershipService;
    private final KeycloakGateway keycloak;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    public PlatformTenantService(TenantRepository tenants, MembershipRepository memberships,
                                 MembershipService membershipService, KeycloakGateway keycloak, AuditService audit,
                                 PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.membershipService = membershipService;
        this.keycloak = keycloak;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** All tenants (slug order) with their INVITED + ACTIVE member count; no case data. */
    public List<TenantView> list() {
        return platform(readOnly, () -> {
            Map<UUID, Long> counts = memberships.countLiveMembersByTenant().stream()
                    .collect(Collectors.toMap(TenantMemberCount::getTenantId, TenantMemberCount::getMembers));
            return tenants.findAll(Sort.by("slug")).stream()
                    .map(t -> view(t, counts.getOrDefault(t.getId(), 0L)))
                    .toList();
        });
    }

    public TenantCreatedView create(CreateTenantRequest request) {
        UUID actor = RequestContextHolder.get().userId();
        String slug = request.slug();
        String name = request.name().trim();
        String adminEmail = request.adminEmail().trim().toLowerCase(Locale.ROOT);

        if (Boolean.TRUE.equals(platform(readOnly, () -> tenants.existsBySlug(slug)))) {
            throw duplicateSlug();
        }
        String orgId;
        try {
            orgId = keycloak.createOrganization(name, slug);
        } catch (DomainException e) {
            if (e.status() == HttpStatus.CONFLICT) {
                throw duplicateSlug();
            }
            throw e;
        }

        Tenant tenant;
        try {
            tenant = platform(transactions, () -> {
                Tenant created = tenants.save(Tenant.create(orgId, slug, name));
                tenants.flush();
                membershipService.createInvitation(created.getId(), adminEmail, TenantRole.TENANT_ADMIN, actor);
                audit.recordPlatform(AuditActions.TENANT_CREATED, TARGET, created.getId().toString(),
                        details("slug", slug, "name", name, "adminEmail", adminEmail));
                return created;
            });
        } catch (RuntimeException e) {
            deleteOrganizationQuietly(orgId, e);
            throw e;
        }

        boolean inviteSent = sendFirstInvitation(tenant, adminEmail);
        return new TenantCreatedView(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getStatus(),
                tenant.getCreatedAt(), adminEmail, inviteSent);
    }

    /** Sets the status; already in that status is a no-op (no audit). Unknown id is 404. */
    public TenantView changeStatus(UUID tenantId, TenantStatus status) {
        return platform(transactions, () -> {
            Tenant tenant = tenants.lockById(tenantId).orElseThrow(PlatformTenantService::tenantNotFound);
            if (tenant.getStatus() != status) {
                tenant.changeStatus(status);
                tenants.flush();
                audit.recordPlatform(status == TenantStatus.SUSPENDED
                                ? AuditActions.TENANT_SUSPENDED : AuditActions.TENANT_ACTIVATED,
                        TARGET, tenant.getId().toString(), details("slug", tenant.getSlug()));
            }
            return view(tenant, memberships.countByTenantIdAndStatusNot(tenantId, MembershipStatus.REMOVED));
        });
    }

    /**
     * Invites (or re-sends a pending invitation to) a TENANT_ADMIN. The Keycloak call is the last step of
     * the transaction: its failure (502) leaves no new row behind. Unknown tenant id is 404.
     */
    public MemberView inviteAdmin(UUID tenantId, String email) {
        UUID actor = RequestContextHolder.get().userId();
        return platform(transactions, () -> {
            if (!tenants.existsById(tenantId)) {
                throw tenantNotFound();
            }
            Membership membership = membershipService.inviteOrResend(tenantId, email, TenantRole.TENANT_ADMIN, actor);
            return membershipService.view(membership);
        });
    }

    private boolean sendFirstInvitation(Tenant tenant, String adminEmail) {
        try {
            keycloak.inviteToOrganization(tenant.getKeycloakOrgId(), adminEmail);
            return true;
        } catch (RuntimeException e) {
            log.warn("Invitation of the first admin of tenant {} failed; tenant kept", tenant.getSlug(), e);
            try {
                platform(transactions, () -> {
                    audit.recordPlatform(AuditActions.TENANT_INVITE_FAILED, TARGET, tenant.getId().toString(),
                            details("slug", tenant.getSlug(), "email", adminEmail,
                                    "error", e.getClass().getSimpleName()));
                    return null;
                });
            } catch (RuntimeException auditFailure) {
                log.error("Could not audit the failed invitation of tenant {}", tenant.getSlug(), auditFailure);
            }
            return false;
        }
    }

    private void deleteOrganizationQuietly(String orgId, RuntimeException cause) {
        try {
            keycloak.deleteOrganization(orgId);
        } catch (RuntimeException e) {
            log.error("Compensation failed: Keycloak organization {} could not be deleted", orgId, e);
            cause.addSuppressed(e);
        }
    }

    /** Runs {@code work} in a transaction that begins in platform mode. */
    private static <T> T platform(TransactionTemplate template, Supplier<T> work) {
        return RequestContextHolder.runAsPlatform(() -> template.execute(status -> work.get()));
    }

    private static TenantView view(Tenant tenant, long memberCount) {
        return new TenantView(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getStatus(), memberCount,
                tenant.getCreatedAt());
    }

    private static Map<String, Object> details(String... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static DomainException duplicateSlug() {
        return new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "Bu kısa ad (slug) başka bir şirket tarafından kullanılıyor. Farklı bir kısa ad seçin.");
    }

    private static DomainException tenantNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                "Şirket bulunamadı. Listeyi yenileyip tekrar deneyin.");
    }
}
