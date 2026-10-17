package com.itiarz.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.common.ProblemDetailsHandler;
import com.itiarz.membership.AppUserRepository;
import com.itiarz.membership.MembershipResolver;
import com.itiarz.tenant.TenantRepository;

class TenantResolutionFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.clear();
    }

    @Test
    void infrastructureFailureBecomesGeneric500Problem() throws Exception {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenThrow(new CannotCreateTransactionException("db host secret-db down"));
        TenantResolutionFilter filter = new TenantResolutionFilter(transactions, mock(AppUserRepository.class),
                mock(TenantRepository.class), mock(MembershipResolver.class), new ProblemDetailsHandler(JSON));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("kc-1").claim("email", "a@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("INTERNAL");
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain("secret-db");
        verify(chain, never()).doFilter(any(), any());
        assertThat(RequestContextHolder.current()).isEmpty();
    }
}
