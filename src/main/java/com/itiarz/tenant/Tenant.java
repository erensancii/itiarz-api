package com.itiarz.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A customer company; mirrors a Keycloak Organization whose alias equals {@code slug}. */
@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    private UUID id;

    @Column(name = "keycloak_org_id", nullable = false, unique = true, length = 80)
    private String keycloakOrgId;

    @Column(nullable = false, unique = true, length = 63)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    /** A new ACTIVE tenant mirroring the Keycloak organization {@code keycloakOrgId} (alias = slug). */
    public static Tenant create(String keycloakOrgId, String slug, String name) {
        Tenant t = new Tenant();
        t.id = UUID.randomUUID();
        t.keycloakOrgId = keycloakOrgId;
        t.slug = slug;
        t.name = name;
        t.status = TenantStatus.ACTIVE;
        t.createdAt = Instant.now();
        return t;
    }

    /** Suspension keeps all data; the tenant's users are refused (403 TENANT_SUSPENDED) until reactivated. */
    public void changeStatus(TenantStatus status) {
        this.status = status;
    }

    /**
     * Points the tenant at another Keycloak organization with the same alias (system use: the demo
     * seeder re-syncs after Keycloak was recreated while the database was kept). Platform mode only.
     */
    public void relinkKeycloakOrganization(String keycloakOrgId) {
        this.keycloakOrgId = keycloakOrgId;
    }

    public UUID getId() { return id; }
    public String getKeycloakOrgId() { return keycloakOrgId; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public TenantStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
