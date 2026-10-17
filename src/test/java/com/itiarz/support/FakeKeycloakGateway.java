package com.itiarz.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.keycloak.KeycloakGateway;
import com.itiarz.keycloak.KeycloakRequestException;

/**
 * In-memory Keycloak for integration tests, mirroring Keycloak 26.8.1 (HttpKeycloakGatewayIT): organizations
 * are unique by alias and by name (409 CONFLICT otherwise); inviting an e-mail that has a pending invitation or
 * belongs to an organization member is 409 CONFLICT (a pending invitation ends when the user joins); e-mail
 * lookups are case-insensitive, and {@link #failNextCall} makes the next gateway call throw once.
 */
public class FakeKeycloakGateway implements KeycloakGateway {

    public record Invitation(String orgId, String email) { }

    private record Org(String id, String name, String alias) { }

    private final Map<String, Org> orgs = new LinkedHashMap<>();
    private final Map<String, Set<String>> members = new LinkedHashMap<>();
    private final List<Invitation> invitations = new ArrayList<>();
    private final Set<Invitation> pendingInvitations = new LinkedHashSet<>();
    private final Map<String, String> usersByEmail = new LinkedHashMap<>();
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextInvitationFailure = new AtomicReference<>();
    private final List<String> deletedOrganizations = new ArrayList<>();
    private String nextOrganizationId;

    public synchronized void reset() {
        orgs.clear();
        members.clear();
        invitations.clear();
        pendingInvitations.clear();
        usersByEmail.clear();
        nextFailure.set(null);
        nextInvitationFailure.set(null);
        deletedOrganizations.clear();
        nextOrganizationId = null;
    }

    /** Pre-registers a Keycloak user (as if it already had an account) and returns its id. */
    public synchronized String registerUser(String email) {
        return usersByEmail.computeIfAbsent(normalize(email), e -> UUID.randomUUID().toString());
    }

    public synchronized List<Invitation> invitations() {
        return List.copyOf(invitations);
    }

    public synchronized Set<String> membersOf(String orgId) {
        return Set.copyOf(members.getOrDefault(orgId, Set.of()));
    }

    public synchronized int organizationCount() {
        return orgs.size();
    }

    /** Ids passed to {@link #deleteOrganization}, in call order. */
    public synchronized List<String> deletedOrganizations() {
        return List.copyOf(deletedOrganizations);
    }

    /** The next {@link #inviteToOrganization} throws {@code failure} once (other calls are unaffected). */
    public void failNextInvitation(RuntimeException failure) {
        nextInvitationFailure.set(failure);
    }

    /** The next {@link #createOrganization} returns this id (once) instead of a random one. */
    public synchronized void nextOrganizationId(String orgId) {
        nextOrganizationId = orgId;
    }

    /** The next gateway call (any method) throws {@code failure} once. */
    public void failNextCall(RuntimeException failure) {
        nextFailure.set(failure);
    }

    @Override
    public synchronized String createOrganization(String name, String alias) {
        maybeFail();
        String key = normalize(alias);
        if (orgs.values().stream().anyMatch(o -> o.alias().equals(key))) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Organization alias already exists");
        }
        if (orgs.values().stream().anyMatch(o -> o.name().equals(name))) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Organization name already exists");
        }
        String id = nextOrganizationId != null ? nextOrganizationId : UUID.randomUUID().toString();
        nextOrganizationId = null;
        Org org = new Org(id, name, key);
        orgs.put(org.id(), org);
        members.put(org.id(), new LinkedHashSet<>());
        return org.id();
    }

    @Override
    public synchronized void deleteOrganization(String orgId) {
        maybeFail();
        deletedOrganizations.add(orgId);
        orgs.remove(orgId);
        members.remove(orgId);
        pendingInvitations.removeIf(i -> i.orgId().equals(orgId));
    }

    @Override
    public synchronized Optional<String> findOrganizationIdByAlias(String alias) {
        maybeFail();
        String key = normalize(alias);
        return orgs.values().stream().filter(o -> o.alias().equals(key)).map(Org::id).findFirst();
    }

    @Override
    public synchronized void inviteToOrganization(String orgId, String email) {
        maybeFail();
        RuntimeException invitationFailure = nextInvitationFailure.getAndSet(null);
        if (invitationFailure != null) {
            throw invitationFailure;
        }
        requireOrg(orgId);
        Invitation invitation = new Invitation(orgId, normalize(email));
        String userId = usersByEmail.get(invitation.email());
        if (pendingInvitations.contains(invitation) || (userId != null && members.get(orgId).contains(userId))) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Already invited or a member");
        }
        pendingInvitations.add(invitation);
        invitations.add(invitation);
    }

    @Override
    public synchronized void addOrganizationMember(String orgId, String keycloakUserId) {
        maybeFail();
        requireOrg(orgId);
        if (!usersByEmail.containsValue(keycloakUserId)) {
            throw new KeycloakRequestException("Unknown user " + keycloakUserId, null);
        }
        if (!members.get(orgId).add(keycloakUserId)) {
            throw new DomainException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Already a member");
        }
        usersByEmail.forEach((email, id) -> {
            if (id.equals(keycloakUserId)) {
                pendingInvitations.remove(new Invitation(orgId, email));
            }
        });
    }

    @Override
    public synchronized void removeOrganizationMember(String orgId, String keycloakUserId) {
        maybeFail();
        Set<String> set = members.get(orgId);
        if (set != null) {
            set.remove(keycloakUserId);
        }
    }

    @Override
    public synchronized Optional<String> findUserIdByEmail(String email) {
        maybeFail();
        return Optional.ofNullable(usersByEmail.get(normalize(email)));
    }

    private void requireOrg(String orgId) {
        if (!orgs.containsKey(orgId)) {
            throw new KeycloakRequestException("Unknown organization " + orgId, null);
        }
    }

    private void maybeFail() {
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
