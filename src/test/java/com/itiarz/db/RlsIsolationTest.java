package com.itiarz.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.itiarz.support.PostgresSupport;

class RlsIsolationTest {

    @BeforeAll
    static void startDatabase() {
        PostgresSupport.container();
    }

    @Test
    void tenantSettingHidesOtherTenantsRows() throws SQLException {
        UUID user = newUser();
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        newCase(tenantA, user);
        newCase(tenantB, user);

        try (Connection app = PostgresSupport.appConnection()) {
            app.setAutoCommit(false);
            setConfig(app, "app.tenant_id", tenantA.toString());
            assertThat(count(app, "SELECT count(*) FROM dispute_cases")).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM dispute_cases WHERE tenant_id <> '" + tenantA + "'"))
                    .isZero();
            app.rollback();
        }
    }

    @Test
    void noTenantSettingReturnsNothing() throws SQLException {
        UUID user = newUser();
        newCase(newTenant(), user);

        try (Connection app = PostgresSupport.appConnection()) {
            assertThat(count(app, "SELECT count(*) FROM dispute_cases")).isZero();
            assertThat(count(app, "SELECT count(*) FROM memberships")).isZero();
        }
    }

    @Test
    void appRoleCannotWriteIntoAnotherTenant() throws SQLException {
        UUID user = newUser();
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();

        try (Connection app = PostgresSupport.appConnection()) {
            app.setAutoCommit(false);
            setConfig(app, "app.tenant_id", tenantA.toString());
            assertThatThrownBy(() -> insertCase(app, tenantB, user))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
            app.rollback();
        }
    }

    @Test
    void appRoleCannotUpdateOrDeleteAudit() throws SQLException {
        try (Connection app = PostgresSupport.appConnection()) {
            assertThatThrownBy(() -> execute(app, "UPDATE audit_events SET action = 'x'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
            assertThatThrownBy(() -> execute(app, "DELETE FROM audit_events"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
        }
    }

    @Test
    void userSeesOnlyOwnMembershipsAcrossTenants() throws SQLException {
        UUID me = newUser();
        UUID other = newUser();
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        newMembership(tenantA, me);
        newMembership(tenantB, me);
        newMembership(tenantA, other);

        try (Connection app = PostgresSupport.appConnection()) {
            app.setAutoCommit(false);
            setConfig(app, "app.user_id", me.toString());
            List<UUID> tenants = new ArrayList<>();
            try (PreparedStatement ps = app.prepareStatement("SELECT tenant_id FROM memberships");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tenants.add(rs.getObject(1, UUID.class));
                }
            }
            assertThat(tenants).containsExactlyInAnyOrder(tenantA, tenantB);
            app.rollback();
        }
    }

    @Test
    void appRoleCannotWriteTenantsWithoutPlatformFlag() throws SQLException {
        try (Connection app = PostgresSupport.appConnection()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> insertTenant(app, UUID.randomUUID()))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
            app.rollback();

            setConfig(app, "app.platform", "on");
            UUID id = UUID.randomUUID();
            insertTenant(app, id);
            app.commit();
            assertThat(count(app, "SELECT count(*) FROM tenants WHERE id = '" + id + "'")).isEqualTo(1);
            app.rollback();
        }
    }

    // --- fixtures (owner role; tenant tables are FORCE RLS so the owner sets app.tenant_id too) ---

    private static UUID newTenant() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = PostgresSupport.ownerConnection()) {
            insertTenant(owner, id);
        }
        return id;
    }

    private static UUID newUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = PostgresSupport.ownerConnection();
                PreparedStatement ps = owner.prepareStatement(
                        "INSERT INTO app_users (id, keycloak_user_id, email, created_at) VALUES (?, ?, ?, now())")) {
            ps.setObject(1, id);
            ps.setString(2, "kc-" + id);
            ps.setString(3, id + "@example.test");
            ps.executeUpdate();
        }
        return id;
    }

    private static void newCase(UUID tenant, UUID user) throws SQLException {
        try (Connection owner = PostgresSupport.ownerConnection()) {
            owner.setAutoCommit(false);
            setConfig(owner, "app.tenant_id", tenant.toString());
            insertCase(owner, tenant, user);
            owner.commit();
        }
    }

    private static void newMembership(UUID tenant, UUID user) throws SQLException {
        try (Connection owner = PostgresSupport.ownerConnection()) {
            owner.setAutoCommit(false);
            setConfig(owner, "app.tenant_id", tenant.toString());
            try (PreparedStatement ps = owner.prepareStatement("""
                    INSERT INTO memberships (id, tenant_id, user_id, invited_email, role, status)
                    VALUES (?, ?, ?, ?, 'ANALYST', 'ACTIVE')""")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenant);
                ps.setObject(3, user);
                ps.setString(4, user + "@example.test");
                ps.executeUpdate();
            }
            owner.commit();
        }
    }

    private static void insertTenant(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tenants (id, keycloak_org_id, slug, name, status, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now())""")) {
            ps.setObject(1, id);
            ps.setString(2, "org-" + id);
            ps.setString(3, "t-" + id);
            ps.setString(4, "Tenant " + id);
            ps.executeUpdate();
        }
    }

    private static void insertCase(Connection c, UUID tenant, UUID user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dispute_cases (id, tenant_id, provider, provider_case_id, order_id, reason, amount,
                                           currency, deadline_utc, status, created_utc, created_by)
                VALUES (?, ?, 'stripe', ?, 'order-1', 'FRAUD', 10.00, 'EUR', now() + interval '7 days',
                        'OPEN', now(), ?)""")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, "pc-" + UUID.randomUUID());
            ps.setObject(4, user);
            ps.executeUpdate();
        }
    }

    private static void setConfig(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, true)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.execute();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
