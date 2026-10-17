package com.itiarz.membership;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.itiarz.security.TenantRole;

/** A user's role in one tenant. {@code userId} stays null until an invitation is accepted. */
@Entity
@Table(name = "memberships")
public class Membership {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "invited_email", nullable = false, length = 320)
    private String invitedEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Membership() {
    }

    /** A new pending invitation; {@code normalizedEmail} must already be lower-case (DB check). */
    static Membership invitation(UUID tenantId, String normalizedEmail, TenantRole role, UUID invitedBy) {
        Membership m = new Membership();
        Instant now = Instant.now();
        m.id = UUID.randomUUID();
        m.tenantId = tenantId;
        m.invitedEmail = normalizedEmail;
        m.role = role;
        m.status = MembershipStatus.INVITED;
        m.invitedBy = invitedBy;
        m.createdAt = now;
        m.updatedAt = now;
        return m;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getInvitedEmail() { return invitedEmail; }
    public TenantRole getRole() { return role; }
    public MembershipStatus getStatus() { return status; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Re-invites a REMOVED row in place (same id); the linked user, if any, is kept until activation. */
    void reinvite(TenantRole role, UUID invitedBy) {
        this.role = role;
        this.invitedBy = invitedBy;
        this.status = MembershipStatus.INVITED;
        touch();
    }

    /** Invitation accepted: binds the user and grants access. */
    void activate(UUID userId) {
        this.userId = userId;
        this.status = MembershipStatus.ACTIVE;
        touch();
    }

    /** Sets the user link without changing the status (seed repair of a row whose link is empty). */
    void bindUser(UUID userId) {
        this.userId = userId;
        touch();
    }

    /** Releases the user link of a non-active row, so the user can be bound to another row of the tenant. */
    void unlinkUser() {
        this.userId = null;
        touch();
    }

    void changeRole(TenantRole role) {
        this.role = role;
        touch();
    }

    /** Soft removal: the row is kept for history and can be re-invited. */
    void remove() {
        this.status = MembershipStatus.REMOVED;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
