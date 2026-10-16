package com.disputepilot.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "dispute_cases", uniqueConstraints =
    @UniqueConstraint(name = "uq_cases_provider_reference", columnNames = {"provider", "provider_case_id"}))
public class DisputeCase {
    @Id
    @Column(nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 80)
    private String provider;

    @Column(name = "provider_case_id", nullable = false, length = 80)
    private String providerCaseId;

    @Column(name = "order_id", nullable = false, length = 80)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CaseReason reason;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "deadline_utc", nullable = false)
    private Instant deadlineUtc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CaseStatus status = CaseStatus.Open;

    @Column(name = "created_utc", nullable = false)
    private Instant createdUtc = Instant.now();

    @OneToMany(mappedBy = "disputeCase", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EvidenceItem> evidence = new ArrayList<>();

    protected DisputeCase() { }

    public DisputeCase(String provider, String providerCaseId, String orderId,
                       CaseReason reason, BigDecimal amount, String currency, Instant deadlineUtc) {
        this.provider = provider;
        this.providerCaseId = providerCaseId;
        this.orderId = orderId;
        this.reason = reason;
        this.amount = amount;
        this.currency = currency;
        this.deadlineUtc = deadlineUtc;
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getProviderCaseId() { return providerCaseId; }
    public String getOrderId() { return orderId; }
    public CaseReason getReason() { return reason; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getDeadlineUtc() { return deadlineUtc; }
    public CaseStatus getStatus() { return status; }
    public Instant getCreatedUtc() { return createdUtc; }
    public List<EvidenceItem> getEvidence() { return evidence; }

    public void setStatus(CaseStatus status) { this.status = status; }
    public void addEvidence(String kind, String note) {
        this.evidence.add(new EvidenceItem(this, kind, note));
    }
}
