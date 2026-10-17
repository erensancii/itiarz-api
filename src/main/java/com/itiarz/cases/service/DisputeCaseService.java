package com.itiarz.cases.service;

import com.itiarz.audit.AuditActions;
import com.itiarz.audit.AuditService;
import com.itiarz.cases.api.CaseDtos.*;
import com.itiarz.cases.domain.*;
import com.itiarz.cases.repo.CaseTransitionRepository;
import com.itiarz.cases.repo.DisputeCaseRepository;
import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.common.FieldValidationException;
import com.itiarz.membership.AppUser;
import com.itiarz.membership.AppUserRepository;
import com.itiarz.membership.MembershipRepository;
import com.itiarz.membership.MembershipStatus;
import com.itiarz.security.Permission;
import com.itiarz.security.RequestContextHolder;
import com.itiarz.security.RolePermissions;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Case workflow within the caller's tenant: state machine, four-eyes rule, assignment. Every status change
 * writes a {@code case_transitions} row and a {@code case.transition} audit event in the same transaction.
 */
@Service
public class DisputeCaseService {
    private final DisputeCaseRepository cases;
    private final CaseTransitionRepository transitions;
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final AuditService audit;

    public DisputeCaseService(DisputeCaseRepository cases, CaseTransitionRepository transitions,
                              MembershipRepository memberships, AppUserRepository users, AuditService audit) {
        this.cases = cases;
        this.transitions = transitions;
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CaseSummary> findAll() {
        List<DisputeCase> all = cases.findAllByTenantIdOrderByDeadlineUtc(RequestContextHolder.requireTenantId());
        Map<UUID, UserRef> refs = userRefs(all.stream().map(DisputeCase::getAssigneeId));
        return all.stream().map(c -> new CaseSummary(c.getId(), c.getProvider(), c.getProviderCaseId(),
            c.getOrderId(), c.getReason(), c.getAmount(), c.getCurrency(), c.getDeadlineUtc(), c.getStatus(),
            refs.get(c.getAssigneeId()), missingEvidence(c), c.getVersion())).toList();
    }

    @Transactional(readOnly = true)
    public CaseDetail findOne(UUID id) {
        return toDetail(getCase(id));
    }

    @Transactional
    public CaseDetail create(CreateCaseRequest request) {
        UUID tenantId = RequestContextHolder.requireTenantId();
        if (!request.deadlineUtc().isAfter(Instant.now())) {
            throw new FieldValidationException("deadlineUtc", "Son tarih gelecekte ve UTC olmalıdır.");
        }
        var provider = request.provider().trim();
        var providerCaseId = request.providerCaseId().trim();
        if (cases.existsByTenantIdAndProviderAndProviderCaseId(tenantId, provider, providerCaseId)) {
            throw duplicateCase();
        }
        var dispute = new DisputeCase(tenantId, actorId(), provider, providerCaseId, request.orderId().trim(),
            request.reason(), request.amount(), request.currency(), request.deadlineUtc());
        try {
            cases.saveAndFlush(dispute);
        } catch (DataIntegrityViolationException e) {
            // concurrent create of the same reference slipped past the pre-check
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_cases_tenant_provider_reference")) {
                throw duplicateCase();
            }
            throw e;
        }
        transitions.save(new CaseTransition(dispute, null, CaseStatus.Open, actorId(), null));
        audit.record(AuditActions.CASE_CREATED, "case", dispute.getId().toString(), Map.of(
            "provider", provider, "providerCaseId", providerCaseId));
        return toDetail(dispute);
    }

    @Transactional
    public CaseDetail addEvidence(UUID caseId, AddEvidenceRequest request) {
        var dispute = getCase(caseId);
        if (!CaseRules.ALLOWED_EVIDENCE.contains(request.kind())) {
            throw new FieldValidationException("kind", "Geçersiz kanıt türü.");
        }
        CaseStatus from = dispute.getStatus();
        if (from == CaseStatus.Submitted || from == CaseStatus.Won || from == CaseStatus.Lost) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.INVALID_TRANSITION,
                "Gönderilmiş veya kapanmış itiraza kanıt eklenemez.");
        }
        dispute.addEvidence(request.kind(), request.note() == null ? "" : request.note().trim(), actorId());
        audit.record(AuditActions.CASE_EVIDENCE_ADDED, "case", dispute.getId().toString(),
            Map.of("kind", request.kind()));
        if (from != CaseStatus.InProgress) {
            // New evidence means work is in progress again; a Ready case must be re-prepared and re-approved.
            dispute.setStatus(CaseStatus.InProgress);
            dispute.setPreparedBy(null);
            recordTransition(dispute, from, CaseStatus.InProgress, "Yeni kanıt eklendi.");
        }
        cases.flush();
        return toDetail(dispute);
    }

    @Transactional
    public CaseDetail transition(UUID caseId, TransitionRequest request) {
        var dispute = getCase(caseId);
        if (!request.version().equals(dispute.getVersion())) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "Kayıt siz çalışırken başka biri tarafından değiştirildi. Sayfayı yenileyip tekrar deneyin.");
        }
        CaseStatus from = dispute.getStatus();
        CaseStatus to = request.to();
        Permission required = CaseRules.requiredPermission(from, to).orElseThrow(() -> new DomainException(
            HttpStatus.CONFLICT, ErrorCode.INVALID_TRANSITION, "Bu durum geçişi geçersiz."));
        if (!hasAuthority(required)) {
            throw new AccessDeniedException("missing " + required.code());
        }
        UUID actor = actorId();
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        switch (to) {
            case Ready -> {
                if (!missingEvidence(dispute).isEmpty()) {
                    throw new DomainException(HttpStatus.CONFLICT, ErrorCode.EVIDENCE_INCOMPLETE,
                        "Zorunlu kanıt türleri henüz tamamlanmadı.");
                }
                dispute.setPreparedBy(actor);
            }
            case InProgress -> {
                if (from == CaseStatus.Ready) {
                    if (reason == null) {
                        throw new FieldValidationException("reason",
                            "Geri gönderirken bir gerekçe yazın (en fazla 500 karakter).");
                    }
                    dispute.setPreparedBy(null);
                }
            }
            case Submitted -> {
                if (actor.equals(dispute.getPreparedBy())) {
                    throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CASE_SELF_APPROVAL,
                        "Hazırladığınız itirazı onaylayamazsınız. Onay için başka bir onaylayıcıya iletin.");
                }
                dispute.setApprovedBy(actor);
            }
            case Won, Lost -> dispute.setOutcomeBy(actor);
            case Open -> { }
        }
        // Submitted ONLY records a human's external action; no bank/PSP call occurs.
        dispute.setStatus(to);
        recordTransition(dispute, from, to, reason);
        cases.flush();
        return toDetail(dispute);
    }

    @Transactional
    public CaseDetail assign(UUID caseId, AssigneeRequest request) {
        UUID tenantId = RequestContextHolder.requireTenantId();
        var dispute = getCase(caseId);
        UUID assignee = request.userId();
        if (assignee != null) {
            boolean eligible = memberships.findByTenantIdAndUserIdAndStatus(tenantId, assignee, MembershipStatus.ACTIVE)
                .map(m -> RolePermissions.of(m.getRole()).contains(Permission.CASE_WRITE))
                .orElse(false);
            if (!eligible) {
                throw new FieldValidationException("userId",
                    "Atanacak kişi bu şirkette etkin ve en az analist rolünde olmalıdır.");
            }
        }
        UUID previous = dispute.getAssigneeId();
        dispute.setAssigneeId(assignee);
        Map<String, Object> details = new HashMap<>();
        details.put("from", previous == null ? null : previous.toString());
        details.put("to", assignee == null ? null : assignee.toString());
        audit.record(AuditActions.CASE_ASSIGNED, "case", dispute.getId().toString(), details);
        cases.flush();
        return toDetail(dispute);
    }

    private void recordTransition(DisputeCase dispute, CaseStatus from, CaseStatus to, String reason) {
        transitions.save(new CaseTransition(dispute, from, to, actorId(), reason));
        Map<String, Object> details = new HashMap<>();
        details.put("from", from.name());
        details.put("to", to.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(AuditActions.CASE_TRANSITION, "case", dispute.getId().toString(), details);
    }

    private DisputeCase getCase(UUID id) {
        return cases.findByIdAndTenantId(id, RequestContextHolder.requireTenantId()).orElseThrow(() ->
            new DomainException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "İtiraz kaydı bulunamadı."));
    }

    private static UUID actorId() {
        return RequestContextHolder.get().userId();
    }

    private static boolean hasAuthority(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(a -> permission.code().equals(a.getAuthority()));
    }

    private static DomainException duplicateCase() {
        return new DomainException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_CASE,
            "Bu sağlayıcı/itiraz numarası bu şirkette zaten kayıtlı.");
    }

    private static List<String> missingEvidence(DisputeCase dispute) {
        Set<String> current = dispute.getEvidence().stream()
            .map(EvidenceItem::getKind).collect(Collectors.toSet());
        return CaseRules.requiredEvidence(dispute.getReason()).stream()
            .filter(kind -> !current.contains(kind)).toList();
    }

    /** One batched app_users lookup for all referenced user ids (no per-row queries); get(null) is null. */
    private Map<UUID, UserRef> userRefs(Stream<UUID> ids) {
        Set<UUID> wanted = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return new HashMap<>();
        }
        return users.findAllById(wanted).stream()
            .collect(Collectors.toMap(AppUser::getId, u -> new UserRef(u.getId(), u.getEmail()),
                (a, b) -> a, HashMap::new));
    }

    private CaseDetail toDetail(DisputeCase c) {
        List<CaseTransition> history = transitions.findByTenantIdAndCaseIdOrderByOccurredAtAscIdAsc(
            c.getTenantId(), c.getId());
        Map<UUID, UserRef> refs = userRefs(Stream.concat(
            Stream.of(c.getAssigneeId(), c.getPreparedBy(), c.getApprovedBy()),
            history.stream().map(CaseTransition::getActorId)));
        var evidence = c.getEvidence().stream()
            .map(item -> new EvidenceResponse(item.getId(), item.getKind(), item.getNote(), item.getCreatedUtc()))
            .toList();
        var transitionResponses = history.stream()
            .map(t -> new TransitionResponse(t.getFromStatus(), t.getToStatus(), refs.get(t.getActorId()),
                t.getReason(), t.getOccurredAt()))
            .toList();
        return new CaseDetail(c.getId(), c.getProvider(), c.getProviderCaseId(), c.getOrderId(), c.getReason(),
            c.getAmount(), c.getCurrency(), c.getDeadlineUtc(), c.getStatus(), refs.get(c.getAssigneeId()),
            missingEvidence(c), c.getVersion(), evidence, refs.get(c.getPreparedBy()), refs.get(c.getApprovedBy()),
            transitionResponses);
    }
}
