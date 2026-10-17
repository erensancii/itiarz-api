package com.itiarz.membership;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.security.Permission;
import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;
import com.itiarz.security.RolePermissions;
import com.itiarz.security.TenantRole;
import com.itiarz.tenant.Tenant;
import com.itiarz.tenant.TenantRepository;

/** Who am I: identity, own memberships across tenants and the active tenant's permissions. */
@RestController
public class MeController {
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;

    public MeController(AppUserRepository users, MembershipRepository memberships, TenantRepository tenants) {
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
    }

    public record UserView(UUID id, String email, String displayName) { }

    public record TenantView(String slug, String name, TenantRole role) { }

    public record MeResponse(UserView user, boolean platformAdmin, List<TenantView> memberships,
                             TenantView activeTenant, List<String> permissions) { }

    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    public MeResponse me() {
        RequestContext context = RequestContextHolder.get();
        AppUser user = users.findById(context.userId()).orElseThrow();

        List<Membership> own = memberships.findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE);
        Map<UUID, Tenant> tenantById = tenants.findAllById(own.stream().map(Membership::getTenantId).toList())
                .stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
        List<TenantView> views = own.stream()
                .filter(m -> tenantById.containsKey(m.getTenantId()))
                .map(m -> {
                    Tenant t = tenantById.get(m.getTenantId());
                    return new TenantView(t.getSlug(), t.getName(), m.getRole());
                })
                .sorted(Comparator.comparing(TenantView::name))
                .toList();

        TenantView active = context.hasTenant()
                ? views.stream().filter(v -> v.slug().equals(context.tenantSlug())).findFirst()
                        // membership removed after tenant resolution (race): same answer as the filter
                        .orElseThrow(() -> new DomainException(HttpStatus.FORBIDDEN, ErrorCode.NO_MEMBERSHIP,
                                "Bu şirkette etkin bir üyeliğiniz yok. Başka bir şirket seçin veya yöneticinizden davet isteyin."))
                : null;
        List<String> permissions = RolePermissions.granted(context.role(), context.platformAdmin()).stream()
                .map(Permission::code).sorted().toList();
        return new MeResponse(new UserView(user.getId(), user.getEmail(), user.getDisplayName()),
                context.platformAdmin(), views, active, permissions);
    }
}
