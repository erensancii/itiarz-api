package com.itiarz.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import com.itiarz.common.DomainException;
import com.itiarz.common.ErrorCode;
import com.itiarz.common.ProblemDetailsHandler;
import com.itiarz.common.TraceIdFilter;
import com.itiarz.membership.AppUser;
import com.itiarz.membership.AppUserRepository;
import com.itiarz.membership.Membership;
import com.itiarz.membership.MembershipResolver;
import com.itiarz.tenant.Tenant;
import com.itiarz.tenant.TenantRepository;

/**
 * Runs after bearer authentication: provisions the app user, resolves the tenant selected by the
 * token's single {@code organization} (uncached, every request) and binds the {@link RequestContext}.
 * Zero or several organizations yield a tenant-less context. Not a bean: registered only in the
 * security chain.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TenantResolutionFilter.class);
    private static final int DISPLAY_NAME_MAX = 200;

    private final TransactionTemplate transactions;
    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final MembershipResolver membershipResolver;
    private final ProblemDetailsHandler problems;

    public TenantResolutionFilter(TransactionTemplate transactions, AppUserRepository users, TenantRepository tenants,
                                  MembershipResolver membershipResolver, ProblemDetailsHandler problems) {
        this.transactions = transactions;
        this.users = users;
        this.tenants = tenants;
        this.membershipResolver = membershipResolver;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            RequestContext context;
            try {
                context = resolve(JwtClaims.from(jwtAuthentication.getToken()));
            } catch (DomainException e) {
                problems.write(response, problems.problem(e.status(), e.code(), e.getMessage(), request));
                return;
            } catch (RuntimeException e) {
                // outside the DispatcherServlet: no @RestControllerAdvice, so render the generic 500 here
                log.error("Tenant resolution failed [traceId={}]", TraceIdFilter.traceId(request), e);
                problems.write(response, problems.internal(request));
                return;
            }
            List<SimpleGrantedAuthority> authorities = RolePermissions
                    .granted(context.role(), context.platformAdmin()).stream()
                    .map(p -> new SimpleGrantedAuthority(p.code()))
                    .toList();
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new JwtAuthenticationToken(
                    jwtAuthentication.getToken(), authorities, jwtAuthentication.getName()));
            SecurityContextHolder.setContext(securityContext);
            chain.doFilter(request, response);
        } finally {
            RequestContextHolder.clear();
        }
    }

    private RequestContext resolve(JwtClaims claims) {
        // 1) identity (app_users has no RLS); 2) bind app.user_id so the self-read membership policy applies.
        AppUser user = transactions.execute(status -> users.provision(
                claims.subject(), claims.email(), truncate(claims.name())));
        RequestContext base = new RequestContext(user.getId(), claims.subject(), user.getEmail(),
                claims.platformAdmin(), null, null, null);
        RequestContextHolder.set(base);
        // Platform admins never act inside a tenant (spec §4.1): the organization claim is ignored.
        if (claims.platformAdmin() || claims.organizations().size() != 1) {
            return base;
        }
        String slug = claims.organizations().get(0);
        RequestContext resolved = transactions.execute(status -> {
            Tenant tenant = tenants.findBySlug(slug).orElseThrow(TenantResolutionFilter::noMembership);
            if (!tenant.isActive()) {
                throw new DomainException(HttpStatus.FORBIDDEN, ErrorCode.TENANT_SUSPENDED,
                        "Bu şirketin hesabı askıya alınmış. Ayrıntılar için şirket yöneticinizle iletişime geçin.");
            }
            Membership membership = membershipResolver.resolveActive(tenant.getId(), user)
                    .orElseThrow(TenantResolutionFilter::noMembership);
            return new RequestContext(base.userId(), base.keycloakUserId(), base.email(), base.platformAdmin(),
                    tenant.getId(), tenant.getSlug(), membership.getRole());
        });
        RequestContextHolder.set(resolved);
        return resolved;
    }

    private static DomainException noMembership() {
        return new DomainException(HttpStatus.FORBIDDEN, ErrorCode.NO_MEMBERSHIP,
                "Bu şirkette etkin bir üyeliğiniz yok. Başka bir şirket seçin veya yöneticinizden davet isteyin.");
    }

    private static String truncate(String name) {
        return name == null || name.length() <= DISPLAY_NAME_MAX ? name : name.substring(0, DISPLAY_NAME_MAX);
    }
}
