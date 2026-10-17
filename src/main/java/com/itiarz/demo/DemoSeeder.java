package com.itiarz.demo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.itiarz.cases.domain.CaseReason;
import com.itiarz.cases.domain.CaseStatus;
import com.itiarz.cases.domain.CaseTransition;
import com.itiarz.cases.domain.DisputeCase;
import com.itiarz.cases.repo.CaseTransitionRepository;
import com.itiarz.cases.repo.DisputeCaseRepository;
import com.itiarz.cases.service.CaseRules;
import com.itiarz.common.DomainException;
import com.itiarz.keycloak.KeycloakGateway;
import com.itiarz.keycloak.KeycloakUnavailableException;
import com.itiarz.membership.AppUser;
import com.itiarz.membership.AppUserRepository;
import com.itiarz.membership.MembershipService;
import com.itiarz.security.RequestContextHolder;
import com.itiarz.security.TenantRole;
import com.itiarz.tenant.Tenant;
import com.itiarz.tenant.TenantRepository;

/**
 * Development demo data (spec §11), run on {@link ApplicationReadyEvent} when {@code app.seed-demo=true}.
 *
 * <p>Per tenant: Keycloak organization by alias (created if missing) → realm users (imported with the
 * realm, looked up by e-mail) added as organization members → one platform-mode transaction for the
 * {@code tenants}, {@code app_users} and ACTIVE {@code memberships} rows → one tenant-mode transaction
 * ({@link RequestContextHolder#runAsTenant}) for the fictional cases, keyed by (tenant, provider,
 * provider_case_id). Every step is idempotent: a re-run creates nothing new and leaves existing cases
 * untouched. Seeded cases satisfy the workflow invariants (creation history row, required evidence for
 * Ready and later, four-eyes: prepared by the analyst, approved by the approver). No audit events are
 * written for seed data. {@code platform@itiarz.local} is a realm role only and gets no membership.
 *
 * <p>A failure (e.g. Keycloak unreachable after a few retries) is logged as a warning; the application
 * keeps running without (complete) demo data.
 */
@Component
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final int ATTEMPTS = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private static final String ACME_ADMIN = "admin@acme.local";
    private static final String ACME_APPROVER = "onay@acme.local";
    private static final String ACME_ANALYST = "analist@acme.local";
    private static final String BETA_ADMIN = "admin@beta.local";
    private static final String CONSULTANT = "danisman@itiarz.local";

    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            ACME_ADMIN, "Acme Admin",
            ACME_APPROVER, "Acme Onay",
            ACME_ANALYST, "Acme Analist",
            "izleyici@acme.local", "Acme Izleyici",
            BETA_ADMIN, "Beta Admin",
            CONSULTANT, "Danisman Itiarz");

    private static final Map<String, String> EVIDENCE_NOTES = Map.of(
            "PaymentRecord", "Demo: ödeme kaydı ve provizyon dökümü.",
            "DeliveryProof", "Demo: kargo teslim tutanağı.",
            "3DSRecord", "Demo: 3D Secure doğrulama kaydı.",
            "RefundRecord", "Demo: iade dekontu.",
            "OrderConfirmation", "Demo: sipariş onay e-postası.",
            "CancellationPolicy", "Demo: iptal koşulları sayfası.",
            "CustomerCommunication", "Demo: müşteriyle yazışma.");

    private record MemberSeed(String email, TenantRole role) { }

    /** {@code preparer} creates the case (and prepares it from Ready on); {@code approver} approves/closes. */
    private record CaseSeed(String provider, String providerCaseId, String orderId, CaseReason reason,
                            String amount, Duration deadlineIn, CaseStatus status, String preparer,
                            String approver, List<String> evidence) { }

    private record TenantSeed(String slug, String name, List<MemberSeed> members, List<CaseSeed> cases) { }

    private static final List<TenantSeed> TENANTS = List.of(
            new TenantSeed("acme", "Acme Ticaret",
                    List.of(new MemberSeed(ACME_ADMIN, TenantRole.TENANT_ADMIN),
                            new MemberSeed(ACME_APPROVER, TenantRole.APPROVER),
                            new MemberSeed(ACME_ANALYST, TenantRole.ANALYST),
                            new MemberSeed("izleyici@acme.local", TenantRole.VIEWER),
                            new MemberSeed(CONSULTANT, TenantRole.ANALYST)),
                    List.of(new CaseSeed("Örnek Banka", "ACME-CB-1001", "ORD-1452", CaseReason.NotReceived,
                                    "2850.00", Duration.ofHours(30), CaseStatus.Open, ACME_ANALYST, null, List.of()),
                            new CaseSeed("Örnek PSP", "ACME-CB-1002", "ORD-1453", CaseReason.Fraud,
                                    "1750.00", Duration.ofDays(6), CaseStatus.InProgress, ACME_ANALYST, null,
                                    List.of("PaymentRecord")),
                            new CaseSeed("Örnek Banka", "ACME-CB-1003", "ORD-1454", CaseReason.Duplicate,
                                    "950.00", Duration.ofDays(4), CaseStatus.Ready, ACME_ANALYST, null,
                                    List.of("PaymentRecord", "RefundRecord")),
                            new CaseSeed("Örnek PSP", "ACME-CB-1004", "ORD-1455", CaseReason.Other,
                                    "4200.00", Duration.ofDays(12), CaseStatus.Won, ACME_ANALYST, ACME_APPROVER,
                                    List.of("PaymentRecord", "OrderConfirmation")))),
            new TenantSeed("beta", "Beta Market",
                    List.of(new MemberSeed(BETA_ADMIN, TenantRole.TENANT_ADMIN),
                            new MemberSeed(CONSULTANT, TenantRole.VIEWER)),
                    List.of(new CaseSeed("Örnek Banka", "BETA-CB-2001", "BM-7781", CaseReason.Cancelled,
                                    "620.50", Duration.ofHours(36), CaseStatus.Open, BETA_ADMIN, null, List.of()),
                            new CaseSeed("Örnek PSP", "BETA-CB-2002", "BM-7782", CaseReason.NotReceived,
                                    "3100.00", Duration.ofDays(9), CaseStatus.InProgress, BETA_ADMIN, null,
                                    List.of("PaymentRecord")))));

    private final boolean enabled;
    private final KeycloakGateway keycloak;
    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final MembershipService membershipService;
    private final DisputeCaseRepository cases;
    private final CaseTransitionRepository transitions;
    private final TransactionTemplate transactions;

    public DemoSeeder(@Value("${app.seed-demo:false}") boolean enabled, KeycloakGateway keycloak,
                      TenantRepository tenants, AppUserRepository users, MembershipService membershipService,
                      DisputeCaseRepository cases, CaseTransitionRepository transitions,
                      PlatformTransactionManager transactionManager) {
        this.enabled = enabled;
        this.keycloak = keycloak;
        this.tenants = tenants;
        this.users = users;
        this.membershipService = membershipService;
        this.cases = cases;
        this.transitions = transitions;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            return;
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                seed();
                return;
            } catch (KeycloakUnavailableException e) {
                if (attempt == ATTEMPTS) {
                    log.warn("Demo seed skipped: Keycloak is unreachable after {} attempts ({}). "
                            + "The application continues without demo data; restart the api to retry.",
                            ATTEMPTS, e.getMessage());
                    return;
                }
                log.info("Demo seed: Keycloak not reachable yet (attempt {}/{}), retrying in {}s",
                        attempt, ATTEMPTS, RETRY_DELAY.toSeconds());
                if (!sleep()) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Demo seed failed; the application continues without (complete) demo data", e);
                return;
            }
        }
    }

    /** Seeds (or completes) the demo data; safe to run any number of times. */
    public void seed() {
        for (TenantSeed seed : TENANTS) {
            String orgId = ensureOrganization(seed);
            Map<String, String> keycloakIds = new LinkedHashMap<>();
            for (MemberSeed member : seed.members()) {
                String keycloakUserId = keycloak.findUserIdByEmail(member.email()).orElseThrow(() ->
                        new IllegalStateException("Keycloak user " + member.email()
                                + " not found; is the itiarz realm imported?"));
                addMember(orgId, keycloakUserId);
                keycloakIds.put(member.email(), keycloakUserId);
            }
            Seeded seeded = RequestContextHolder.runAsPlatform(() -> transactions.execute(status ->
                    seedTenantRows(seed, orgId, keycloakIds)));
            int created = RequestContextHolder.runAsTenant(seeded.tenantId(), () -> transactions.execute(status ->
                    seedCases(seeded.tenantId(), seed.cases(), seeded.userIds())));
            log.info("Demo seed: tenant {} ready ({} members, {} new cases)", seed.slug(), seed.members().size(),
                    created);
        }
        log.info("Demo seed completed");
    }

    private record Seeded(UUID tenantId, Map<String, UUID> userIds) { }

    private String ensureOrganization(TenantSeed seed) {
        return keycloak.findOrganizationIdByAlias(seed.slug()).orElseGet(() -> {
            try {
                return keycloak.createOrganization(seed.name(), seed.slug());
            } catch (DomainException e) {
                if (e.status() == HttpStatus.CONFLICT) { // created concurrently
                    return keycloak.findOrganizationIdByAlias(seed.slug()).orElseThrow(() -> e);
                }
                throw e;
            }
        });
    }

    private void addMember(String orgId, String keycloakUserId) {
        try {
            keycloak.addOrganizationMember(orgId, keycloakUserId);
        } catch (DomainException e) {
            if (e.status() != HttpStatus.CONFLICT) { // 409: already a member
                throw e;
            }
        }
    }

    private Seeded seedTenantRows(TenantSeed seed, String orgId, Map<String, String> keycloakIds) {
        Tenant tenant = tenants.findBySlug(seed.slug()).orElseGet(() ->
                tenants.save(Tenant.create(orgId, seed.slug(), seed.name())));
        if (!orgId.equals(tenant.getKeycloakOrgId())) {
            log.warn("Demo seed: tenant {} pointed at Keycloak organization {}, relinking to {}",
                    seed.slug(), tenant.getKeycloakOrgId(), orgId);
            tenant.relinkKeycloakOrganization(orgId);
        }
        tenants.flush();
        Map<String, UUID> userIds = new LinkedHashMap<>();
        for (MemberSeed member : seed.members()) {
            AppUser user = users.provision(keycloakIds.get(member.email()), member.email(),
                    DISPLAY_NAMES.get(member.email()));
            membershipService.ensureActiveMember(tenant.getId(), user.getId(), member.email(), member.role());
            userIds.put(member.email(), user.getId());
        }
        return new Seeded(tenant.getId(), userIds);
    }

    /** Creates the missing cases with a history consistent with the workflow; returns how many were new. */
    private int seedCases(UUID tenantId, List<CaseSeed> seeds, Map<String, UUID> userIds) {
        int created = 0;
        for (CaseSeed seed : seeds) {
            if (cases.existsByTenantIdAndProviderAndProviderCaseId(tenantId, seed.provider(), seed.providerCaseId())) {
                continue;
            }
            createCase(tenantId, seed, userIds);
            created++;
        }
        return created;
    }

    private void createCase(UUID tenantId, CaseSeed seed, Map<String, UUID> userIds) {
        UUID preparer = userIds.get(seed.preparer());
        UUID approver = seed.approver() == null ? null : userIds.get(seed.approver());
        Instant now = Instant.now();
        DisputeCase dispute = new DisputeCase(tenantId, preparer, seed.provider(), seed.providerCaseId(),
                seed.orderId(), seed.reason(), new BigDecimal(seed.amount()), "TRY", now.plus(seed.deadlineIn()));
        dispute.setAssigneeId(preparer);
        seed.evidence().forEach(kind -> dispute.addEvidence(kind, EVIDENCE_NOTES.get(kind), preparer));

        CaseStatus target = seed.status();
        if (target.compareTo(CaseStatus.Ready) >= 0
                && !seed.evidence().containsAll(CaseRules.requiredEvidence(seed.reason()))) {
            throw new IllegalStateException("Demo case " + seed.providerCaseId() + " lacks required evidence");
        }
        if (target.compareTo(CaseStatus.Submitted) >= 0 && (approver == null || approver.equals(preparer))) {
            throw new IllegalStateException("Demo case " + seed.providerCaseId() + " needs a distinct approver");
        }
        if (target.compareTo(CaseStatus.Ready) >= 0) {
            dispute.setPreparedBy(preparer);
        }
        if (target.compareTo(CaseStatus.Submitted) >= 0) {
            dispute.setApprovedBy(approver);
        }
        if (target == CaseStatus.Won || target == CaseStatus.Lost) {
            dispute.setOutcomeBy(approver);
        }
        dispute.setStatus(target);
        cases.saveAndFlush(dispute);

        // history: Open → InProgress → Ready → Submitted → Won/Lost, up to the target status
        List<CaseStatus> path = List.of(CaseStatus.Open, CaseStatus.InProgress, CaseStatus.Ready,
                CaseStatus.Submitted, target == CaseStatus.Lost ? CaseStatus.Lost : CaseStatus.Won);
        CaseStatus from = null;
        int step = 0;
        for (CaseStatus to : path) {
            UUID actor = to.compareTo(CaseStatus.Submitted) >= 0 ? approver : preparer;
            transitions.save(new CaseTransition(dispute, from, to, actor, null, now.plusMillis(step++)));
            if (to == target) {
                break;
            }
            from = to;
        }
    }

    private static boolean sleep() {
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
