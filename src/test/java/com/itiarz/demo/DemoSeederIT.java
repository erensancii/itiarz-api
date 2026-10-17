package com.itiarz.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.itiarz.support.IntegrationTest;
import com.itiarz.support.PostgresSupport;

/** Demo seeder against the fake Keycloak (users pre-registered, as the realm import provides them). */
class DemoSeederIT extends IntegrationTest {

    private static final List<String> REALM_USERS = List.of("platform@itiarz.local", "admin@acme.local",
            "onay@acme.local", "analist@acme.local", "izleyici@acme.local", "admin@beta.local",
            "danisman@itiarz.local");

    @Autowired
    DemoSeeder seeder;

    private final Map<String, String> keycloakIds = new HashMap<>();

    @BeforeEach
    void registerRealmUsers() {
        REALM_USERS.forEach(email -> keycloakIds.put(email, fakeKeycloak.registerUser(email)));
    }

    @Test
    void seedCreatesTenantsMembershipsAndCases() throws SQLException {
        seeder.seed();

        String acmeOrg = fakeKeycloak.findOrganizationIdByAlias("acme").orElseThrow();
        String betaOrg = fakeKeycloak.findOrganizationIdByAlias("beta").orElseThrow();
        assertThat(fakeKeycloak.membersOf(acmeOrg)).containsExactlyInAnyOrder(
                kc("admin@acme.local"), kc("onay@acme.local"), kc("analist@acme.local"),
                kc("izleyici@acme.local"), kc("danisman@itiarz.local"));
        assertThat(fakeKeycloak.membersOf(betaOrg)).containsExactlyInAnyOrder(
                kc("admin@beta.local"), kc("danisman@itiarz.local"));

        try (Connection c = platformConnection()) {
            assertThat(row(c, "SELECT name, keycloak_org_id, status FROM tenants WHERE slug = 'acme'"))
                    .containsExactly("Acme Ticaret", acmeOrg, "ACTIVE");
            assertThat(row(c, "SELECT name, keycloak_org_id, status FROM tenants WHERE slug = 'beta'"))
                    .containsExactly("Beta Market", betaOrg, "ACTIVE");

            assertThat(members(c, "acme")).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "admin@acme.local", "TENANT_ADMIN/ACTIVE/" + kc("admin@acme.local") + "/admin@acme.local",
                    "onay@acme.local", "APPROVER/ACTIVE/" + kc("onay@acme.local") + "/onay@acme.local",
                    "analist@acme.local", "ANALYST/ACTIVE/" + kc("analist@acme.local") + "/analist@acme.local",
                    "izleyici@acme.local", "VIEWER/ACTIVE/" + kc("izleyici@acme.local") + "/izleyici@acme.local",
                    "danisman@itiarz.local",
                    "ANALYST/ACTIVE/" + kc("danisman@itiarz.local") + "/danisman@itiarz.local"));
            assertThat(members(c, "beta")).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "admin@beta.local", "TENANT_ADMIN/ACTIVE/" + kc("admin@beta.local") + "/admin@beta.local",
                    "danisman@itiarz.local",
                    "VIEWER/ACTIVE/" + kc("danisman@itiarz.local") + "/danisman@itiarz.local"));
            // the platform admin is a realm role only: no membership anywhere
            assertThat(count(c, "SELECT count(*) FROM memberships WHERE invited_email = 'platform@itiarz.local'"))
                    .isZero();
        }

        UUID acme = tenantId("acme");
        UUID beta = tenantId("beta");
        try (Connection c = tenantConnection(acme)) {
            List<String> statuses = column(c, "SELECT status FROM dispute_cases ORDER BY status");
            assertThat(statuses).hasSize(4).doesNotHaveDuplicates();
            assertThat(count(c, "SELECT count(*) FROM dispute_cases WHERE version = 0")).isEqualTo(4);
            assertThat(count(c, "SELECT count(*) FROM dispute_cases WHERE deadline_utc < now() + interval '2 days'"))
                    .isPositive();
            // every case has its creation history row
            assertThat(count(c, "SELECT count(*) FROM dispute_cases d WHERE NOT EXISTS (SELECT 1 FROM "
                    + "case_transitions t WHERE t.case_id = d.id AND t.from_status IS NULL AND t.to_status = 'Open')"))
                    .isZero();

            List<String> ready = row(c, "SELECT u.email, d.reason FROM dispute_cases d "
                    + "JOIN app_users u ON u.id = d.prepared_by WHERE d.status = 'Ready'");
            // by e-mail: cases persist across tests while the fake hands out new Keycloak ids per test
            assertThat(ready.get(0)).isEqualTo("analist@acme.local");
            assertThat(column(c, "SELECT e.kind FROM evidence_items e JOIN dispute_cases d ON d.id = e.case_id "
                    + "WHERE d.status = 'Ready'"))
                    .containsAll(com.itiarz.cases.service.CaseRules.requiredEvidence(
                            com.itiarz.cases.domain.CaseReason.valueOf(ready.get(1))));
            assertThat(row(c, "SELECT to_status FROM case_transitions t JOIN dispute_cases d ON d.id = t.case_id "
                    + "WHERE d.status = 'Ready' ORDER BY t.occurred_at DESC, t.id DESC LIMIT 1"))
                    .containsExactly("Ready");
        }
        try (Connection c = tenantConnection(beta)) {
            assertThat(count(c, "SELECT count(*) FROM dispute_cases")).isEqualTo(2);
            assertThat(column(c, "SELECT status FROM dispute_cases")).doesNotHaveDuplicates();
        }
    }

    @Test
    void seedIsIdempotent() throws SQLException {
        seeder.seed();
        Map<String, Long> first = snapshot();
        int orgs = fakeKeycloak.organizationCount();
        String acmeOrg = fakeKeycloak.findOrganizationIdByAlias("acme").orElseThrow();
        var acmeMembers = fakeKeycloak.membersOf(acmeOrg);

        seeder.seed();

        assertThat(snapshot()).isEqualTo(first);
        assertThat(fakeKeycloak.organizationCount()).isEqualTo(orgs);
        assertThat(fakeKeycloak.membersOf(acmeOrg)).isEqualTo(acmeMembers);
        assertThat(first.get("tenants")).isEqualTo(2);
        assertThat(first.get("acme.memberships")).isEqualTo(5);
        assertThat(first.get("beta.memberships")).isEqualTo(2);
        assertThat(first.get("acme.cases")).isEqualTo(4);
        assertThat(first.get("beta.cases")).isEqualTo(2);
    }

    private Map<String, Long> snapshot() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = platformConnection()) {
            counts.put("tenants", count(c, "SELECT count(*) FROM tenants WHERE slug IN ('acme', 'beta')"));
            counts.put("app_users", count(c, "SELECT count(*) FROM app_users WHERE email IN ("
                    + String.join(",", REALM_USERS.stream().map(e -> "'" + e + "'").toList()) + ")"));
            for (String slug : List.of("acme", "beta")) {
                counts.put(slug + ".memberships", count(c, "SELECT count(*) FROM memberships m "
                        + "JOIN tenants t ON t.id = m.tenant_id WHERE t.slug = '" + slug + "'"));
                counts.put(slug + ".updated", count(c, "SELECT coalesce(max(extract(epoch FROM m.updated_at) "
                        + "* 1000000), 0)::bigint FROM memberships m JOIN tenants t ON t.id = m.tenant_id "
                        + "WHERE t.slug = '" + slug + "'"));
            }
        }
        for (String slug : List.of("acme", "beta")) {
            try (Connection c = tenantConnection(tenantId(slug))) {
                counts.put(slug + ".cases", count(c, "SELECT count(*) FROM dispute_cases"));
                counts.put(slug + ".evidence", count(c, "SELECT count(*) FROM evidence_items"));
                counts.put(slug + ".transitions", count(c, "SELECT count(*) FROM case_transitions"));
                counts.put(slug + ".versions", count(c, "SELECT coalesce(sum(version), 0) FROM dispute_cases"));
            }
        }
        return counts;
    }

    private String kc(String email) {
        return keycloakIds.get(email);
    }

    private static Map<String, String> members(Connection c, String slug) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT m.invited_email, m.role, m.status, "
                + "u.keycloak_user_id, u.email FROM memberships m JOIN tenants t ON t.id = m.tenant_id "
                + "LEFT JOIN app_users u ON u.id = m.user_id WHERE t.slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getString(2) + "/" + rs.getString(3) + "/" + rs.getString(4)
                            + "/" + rs.getString(5));
                }
            }
        }
        return result;
    }

    private static UUID tenantId(String slug) throws SQLException {
        try (Connection c = PostgresSupport.appConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM tenants WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("tenant %s exists", slug).isTrue();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static Connection platformConnection() throws SQLException {
        return withSetting("app.platform", "on");
    }

    private static Connection tenantConnection(UUID tenantId) throws SQLException {
        return withSetting("app.tenant_id", tenantId.toString());
    }

    private static Connection withSetting(String name, String value) throws SQLException {
        Connection c = PostgresSupport.appConnection();
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, false)")) {
            ps.setString(1, name);
            ps.setString(2, value);
            ps.execute();
        }
        return c;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> row(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<String> values = new ArrayList<>();
            if (rs.next()) {
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    values.add(rs.getString(i));
                }
            }
            return values;
        }
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }
}
