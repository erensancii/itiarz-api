package com.itiarz.security;

import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.itiarz.common.ProblemDetailsHandler;
import com.itiarz.membership.AppUserRepository;
import com.itiarz.membership.MembershipResolver;
import com.itiarz.tenant.TenantRepository;

/**
 * Stateless Bearer-only API. CSRF is off because the BFF (itiarz-web) owns the browser session and
 * its CSRF protection; CORS is off because browsers never call the API directly.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    public static final String AUDIENCE = "itiarz-api";

    /** The one place token validation rules live; the test decoder reuses it with its own key. */
    public static OAuth2TokenValidator<Jwt> jwtValidator(String issuer) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(AUDIENCE));
        // createDefaultWithIssuer = JwtTimestampValidator + JwtIssuerValidator(issuer)
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience);
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.keycloak.internal-url}") String internalUrl,
                          @Value("${app.security.keycloak.public-url}") String publicUrl,
                          @Value("${app.security.keycloak.realm}") String realm) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(internalUrl + "/realms/" + realm + "/protocol/openid-connect/certs")
                .build();
        decoder.setJwtValidator(jwtValidator(publicUrl + "/realms/" + realm));
        return decoder;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder, ProblemDetailsHandler problems,
                                    PlatformTransactionManager transactionManager, AppUserRepository users,
                                    TenantRepository tenants, MembershipResolver membershipResolver)
            throws Exception {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            bearer.commence(request, response, ex); // sets WWW-Authenticate
            problems.write(response, problems.unauthorized(request));
        };
        AccessDeniedHandler forbidden = (request, response, ex) ->
                problems.write(response, problems.forbidden(request));
        TenantResolutionFilter tenantResolution = new TenantResolutionFilter(
                new TransactionTemplate(transactionManager), users, tenants, membershipResolver, problems);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .addFilterAfter(tenantResolution, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
