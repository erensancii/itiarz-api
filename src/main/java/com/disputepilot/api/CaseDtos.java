package com.disputepilot.api;

import com.disputepilot.domain.CaseReason;
import com.disputepilot.domain.CaseStatus;
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

    public record ChangeStatusRequest(@NotNull CaseStatus status) { }

    public record EvidenceResponse(UUID id, String kind, String note, Instant createdUtc) { }

    public record CaseResponse(
        UUID id, String provider, String providerCaseId, String orderId,
        CaseReason reason, BigDecimal amount, String currency, Instant deadlineUtc,
        CaseStatus status, Instant createdUtc, List<String> missing,
        List<EvidenceResponse> evidence
    ) { }
}
