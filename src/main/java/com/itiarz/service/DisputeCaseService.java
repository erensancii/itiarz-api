package com.itiarz.service;

import com.itiarz.api.CaseDtos.*;
import com.itiarz.domain.*;
import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.repo.DisputeCaseRepository;
import com.itiarz.security.RequestContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DisputeCaseService {
    private final DisputeCaseRepository repository;

    public DisputeCaseService(DisputeCaseRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CaseResponse> findAll() {
        // Interim guard until Task 6 rewrites this service; RLS scopes the rows to the tenant.
        RequestContextHolder.requireTenantId();
        return repository.findAllWithEvidenceOrdered().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CaseResponse findOne(UUID id) {
        return toResponse(getCase(id));
    }

    @Transactional
    public CaseResponse create(CreateCaseRequest request) {
        if (!request.deadlineUtc().isAfter(Instant.now())) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION, "Son tarih gelecekte ve UTC olmalıdır.");
        }
        var provider = request.provider().trim();
        var providerCaseId = request.providerCaseId().trim();
        var orderId = request.orderId().trim();
        if (repository.existsByProviderAndProviderCaseId(provider, providerCaseId)) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_CASE, "Bu sağlayıcı/itiraz numarası zaten kayıtlı.");
        }
        var dispute = new DisputeCase(provider, providerCaseId, orderId, request.reason(),
            request.amount(), request.currency(), request.deadlineUtc());
        return toResponse(repository.save(dispute));
    }

    @Transactional
    public CaseResponse addEvidence(UUID caseId, AddEvidenceRequest request) {
        var dispute = getCase(caseId);
        if (!CaseRules.ALLOWED_EVIDENCE.contains(request.kind())) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION, "Geçersiz kanıt türü.");
        }
        if (dispute.getStatus() == CaseStatus.Submitted || dispute.getStatus() == CaseStatus.Won
                || dispute.getStatus() == CaseStatus.Lost) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.INVALID_TRANSITION, "Gönderilmiş/kapanmış itiraz değiştirilemez.");
        }
        dispute.addEvidence(request.kind(), request.note() == null ? "" : request.note().trim());
        // Adding new evidence to a Ready case requires a human to re-confirm readiness.
        dispute.setStatus(CaseStatus.InProgress);
        return toResponse(dispute);
    }

    @Transactional
    public CaseResponse changeStatus(UUID caseId, ChangeStatusRequest request) {
        var dispute = getCase(caseId);
        if (!CaseRules.validTransition(dispute.getStatus(), request.status())) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.INVALID_TRANSITION, "Bu durum geçişi geçersiz.");
        }
        if (request.status() == CaseStatus.Ready && !missingEvidence(dispute).isEmpty()) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.EVIDENCE_INCOMPLETE, "Zorunlu kanıt türleri henüz tamamlanmadı.");
        }
        // Submitted ONLY records a human's external action; no bank/PSP call occurs.
        dispute.setStatus(request.status());
        return toResponse(dispute);
    }

    private DisputeCase getCase(UUID id) {
        RequestContextHolder.requireTenantId();
        return repository.findWithEvidenceById(id).orElseThrow(() ->
            new DomainException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "İtiraz kaydı bulunamadı."));
    }

    private List<String> missingEvidence(DisputeCase dispute) {
        Set<String> current = dispute.getEvidence().stream()
            .map(EvidenceItem::getKind).collect(Collectors.toSet());
        return CaseRules.requiredEvidence(dispute.getReason()).stream()
            .filter(kind -> !current.contains(kind)).toList();
    }

    private CaseResponse toResponse(DisputeCase dispute) {
        var evidence = dispute.getEvidence().stream()
            .map(item -> new EvidenceResponse(item.getId(), item.getKind(),
                item.getNote(), item.getCreatedUtc()))
            .toList();
        return new CaseResponse(dispute.getId(), dispute.getProvider(), dispute.getProviderCaseId(),
            dispute.getOrderId(), dispute.getReason(), dispute.getAmount(), dispute.getCurrency(),
            dispute.getDeadlineUtc(), dispute.getStatus(), dispute.getCreatedUtc(),
            missingEvidence(dispute), evidence);
    }
}
