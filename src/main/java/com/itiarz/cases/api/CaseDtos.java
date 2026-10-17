package com.itiarz.cases.api;

import com.itiarz.cases.domain.CaseReason;
import com.itiarz.cases.domain.CaseStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CaseDtos {
    private CaseDtos() { }

    public record CreateCaseRequest(
        @NotBlank @Size(max = 80) String provider,
        @NotBlank @Size(max = 80) String providerCaseId,
        @NotBlank @Size(max = 80) String orderId,
        @NotNull CaseReason reason,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "TRY|USD|EUR") String currency,
        @NotNull Instant deadlineUtc
    ) { }

    public record AddEvidenceRequest(
        @NotBlank String kind,
        @Size(max = 500) String note
    ) { }

    /** {@code version} is the case version the client last saw; a mismatch is 409 CONFLICT. */
    public record TransitionRequest(
        @NotNull CaseStatus to,
        @Size(max = 500) String reason,
        @NotNull Long version
    ) { }

    /** {@code userId} null removes the assignee. */
    public record AssigneeRequest(UUID userId) { }

    public record UserRef(UUID id, String email) { }

    public record EvidenceResponse(UUID id, String kind, String note, Instant createdUtc) { }

    public record TransitionResponse(CaseStatus from, CaseStatus to, UserRef actor, String reason,
                                     Instant occurredAt) { }

    public record CaseSummary(
        UUID id, String provider, String providerCaseId, String orderId,
        CaseReason reason, BigDecimal amount, String currency, Instant deadlineUtc,
        CaseStatus status, UserRef assignee, List<String> missing, long version
    ) { }

    public record CaseDetail(
        UUID id, String provider, String providerCaseId, String orderId,
        CaseReason reason, BigDecimal amount, String currency, Instant deadlineUtc,
        CaseStatus status, UserRef assignee, List<String> missing, long version,
        List<EvidenceResponse> evidence, UserRef preparedBy, UserRef approvedBy,
        List<TransitionResponse> transitions
    ) { }
}
