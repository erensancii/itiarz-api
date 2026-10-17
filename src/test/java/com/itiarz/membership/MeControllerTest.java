package com.itiarz.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;
import com.itiarz.security.TenantRole;
import com.itiarz.tenant.TenantRepository;

class MeControllerTest {

    @AfterEach
    void clear() {
        RequestContextHolder.clear();
    }

    @Test
    void activeTenantMissingFromMembershipsIsNoMembership() {
        AppUserRepository users = mock(AppUserRepository.class);
        MembershipRepository memberships = mock(MembershipRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(new AppUser()));
        when(memberships.findByUserIdAndStatus(any(), any())).thenReturn(List.of());
        when(tenants.findAllById(any())).thenReturn(List.of());
        // membership removed between tenant resolution and the controller's transaction
        RequestContextHolder.set(new RequestContext(userId, "kc-1", "a@example.com", false,
                UUID.randomUUID(), "acme", TenantRole.ANALYST));

        assertThatThrownBy(() -> new MeController(users, memberships, tenants).me())
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_MEMBERSHIP));
    }
}
