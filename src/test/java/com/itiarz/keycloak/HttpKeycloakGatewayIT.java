package com.itiarz.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;

/**
 * Runs the gateway against a real Keycloak (same pinned tag as docker-compose.yml) that imports the real
 * {@code infra/keycloak/itiarz-realm.json}, with a mailpit container reachable as {@code mailpit} so the
 * realm's SMTP settings work and invitations are verifiable.
 *
 * <p>Findings recorded in {@link HttpKeycloakGateway}'s class comment.
 */
class HttpKeycloakGatewayIT {

    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.1";
    private static final String MAILPIT_IMAGE = "axllent/mailpit:v1.31.6";
    // default of ${KC_ADMIN_CLIENT_SECRET:...} in the realm file
    private static final String ADMIN_SECRET = "admin_secret_dev_only_change_me";

    private static Network network;
    private static GenericContainer<?> mailpit;
    private static GenericContainer<?> keycloak;
    private static String baseUrl;
    private static HttpKeycloakGateway gateway;

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        mailpit = new GenericContainer<>(MAILPIT_IMAGE)
                .withNetwork(network).withNetworkAliases("mailpit")
                .withExposedPorts(8025, 1025)
                .waitingFor(Wait.forHttp("/livez").forPort(8025));
        mailpit.start();
        keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
                .withNetwork(network)
                .withCommand("start-dev", "--import-realm", "--health-enabled=true")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin_test_only")
                .withCopyFileToContainer(MountableFile.forHostPath(realmFile()),
                        "/opt/keycloak/data/import/itiarz-realm.json")
                .withExposedPorts(8080, 9000)
                .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(4)));
        keycloak.start();
        baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        KeycloakProperties props = new KeycloakProperties(baseUrl, "itiarz", "itiarz-api-admin", ADMIN_SECRET);
        gateway = new HttpKeycloakGateway(KeycloakConfig.adminRestClient(props), props.realm());
    }

    @AfterAll
    static void stop() {
        if (keycloak != null) {
            keycloak.stop();
        }
        if (mailpit != null) {
            mailpit.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static Path realmFile() {
        for (String candidate : new String[] {"../infra/keycloak/itiarz-realm.json", "infra/keycloak/itiarz-realm.json"}) {
            Path p = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("itiarz-realm.json not found relative to " + Path.of("").toAbsolutePath());
    }

    private static String alias() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createFindDeleteOrganization() {
        String alias = alias();
        assertThat(gateway.findOrganizationIdByAlias(alias)).isEmpty();

        String id = gateway.createOrganization("Org " + alias, alias);

        assertThat(id).isNotBlank();
        assertThat(gateway.findOrganizationIdByAlias(alias)).contains(id);
        assertThat(gateway.findOrganizationIdByAlias(alias.toUpperCase())).contains(id);

        gateway.deleteOrganization(id);
        assertThat(gateway.findOrganizationIdByAlias(alias)).isEmpty();
    }

    @Test
    void addAndRemoveMember() {
        String id = gateway.createOrganization("Members", alias());
        String userId = gateway.findUserIdByEmail("analist@acme.local").orElseThrow();

        gateway.addOrganizationMember(id, userId);
        // adding twice is a conflict from Keycloak's side
        assertThatThrownBy(() -> gateway.addOrganizationMember(id, userId))
                .isInstanceOf(DomainException.class);

        gateway.removeOrganizationMember(id, userId);
        // removing a non-member is not an error for our callers
        gateway.removeOrganizationMember(id, userId);
        gateway.deleteOrganization(id);
    }

    @Test
    void inviteSendsWithoutError() {
        String id = gateway.createOrganization("Invites", alias());
        String email = "davetli-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

        gateway.inviteToOrganization(id, email);

        String messages = org.springframework.web.client.RestClient.create(
                        "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025))
                .get().uri("/api/v1/search?query=to:" + email).retrieve().body(String.class);
        assertThat(messages).contains(email);
        gateway.deleteOrganization(id);
    }

    @Test
    void duplicateAliasIsConflict() {
        String alias = alias();
        String id = gateway.createOrganization("Dup", alias);

        assertThatThrownBy(() -> gateway.createOrganization("Dup 2", alias))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONFLICT);
                });
        gateway.deleteOrganization(id);
    }

    @Test
    void duplicateNameWithDifferentAliasIsConflict() {
        String name = "Ayni Ad " + alias();
        String id = gateway.createOrganization(name, alias());

        assertThatThrownBy(() -> gateway.createOrganization(name, alias()))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
        gateway.deleteOrganization(id);
    }

    @Test
    void invitingAnExistingMemberIsConflict() {
        String id = gateway.createOrganization("Invite member " + alias(), alias());
        String userId = gateway.findUserIdByEmail("analist@acme.local").orElseThrow();
        gateway.addOrganizationMember(id, userId);

        assertThatThrownBy(() -> gateway.inviteToOrganization(id, "analist@acme.local"))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
        gateway.deleteOrganization(id);
    }

    @Test
    void reinvitingAPendingInvitationIsConflict() {
        String id = gateway.createOrganization("Reinvite " + alias(), alias());
        String email = "tekrar-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        gateway.inviteToOrganization(id, email);

        assertThatThrownBy(() -> gateway.inviteToOrganization(id, email))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
        gateway.deleteOrganization(id);
    }

    @Test
    void findUserIdByEmailIsCaseInsensitive() {
        String lower = gateway.findUserIdByEmail("analist@acme.local").orElseThrow();
        assertThat(gateway.findUserIdByEmail("Analist@ACME.local")).contains(lower);
        assertThat(gateway.findUserIdByEmail("nobody@acme.local")).isEmpty();
    }

    @Test
    void addingMemberToUnknownOrganizationIsARequestError() {
        String userId = gateway.findUserIdByEmail("analist@acme.local").orElseThrow();
        assertThatThrownBy(() -> gateway.addOrganizationMember(UUID.randomUUID().toString(), userId))
                .isInstanceOf(KeycloakRequestException.class);
    }

    @Test
    void findUserIdByEmailEncodesPlusSign() {
        String email = "ali+etiket-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String adminToken = RestClient.create(baseUrl).post()
                .uri("/realms/itiarz/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=itiarz-api-admin&client_secret=" + ADMIN_SECRET)
                .retrieve().body(JsonNode.class).get("access_token").asText();
        RestClient.create(baseUrl).post().uri("/admin/realms/itiarz/users")
                .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", email, "email", email, "enabled", true))
                .retrieve().toBodilessEntity();

        assertThat(gateway.findUserIdByEmail(email)).isPresent();
    }

    @Test
    void propertiesToStringRedactsSecret() {
        assertThat(new KeycloakProperties("http://kc", "itiarz", "id", "s3cret").toString())
                .doesNotContain("s3cret");
    }

    @Test
    void unreachableKeycloakIsUnavailable() {
        KeycloakProperties props = new KeycloakProperties("http://127.0.0.1:1", "itiarz", "itiarz-api-admin", "x");
        HttpKeycloakGateway dead = new HttpKeycloakGateway(KeycloakConfig.adminRestClient(props), "itiarz");
        assertThatThrownBy(() -> dead.findUserIdByEmail("a@b.c")).isInstanceOf(KeycloakUnavailableException.class);
    }
}
