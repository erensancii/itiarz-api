package com.itiarz.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itiarz.support.IntegrationTest;
import com.itiarz.support.TestTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/** Every rejected token must yield 401 problem+json (production validators via SecurityConfig.jwtValidator). */
class JwtValidationIT extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JWTClaimsSet.Builder claims() {
        String email = unique("jwt") + "@example.com";
        return TestTokens.claims(subOf(email), email, null, false);
    }

    @Test
    void validTokenIsAccepted() {
        assertThat(get("/api/me", TestTokens.sign(claims().build())).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void wrongIssuerReturns401() throws Exception {
        assertUnauthorized(TestTokens.sign(claims().issuer("http://evil.example/realms/itiarz").build()));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        assertUnauthorized(TestTokens.sign(claims()
                .issueTime(Date.from(past.minusSeconds(300)))
                .expirationTime(Date.from(past))
                .build()));
    }

    @Test
    void signatureByUntrustedKeyReturns401() throws Exception {
        assertUnauthorized(TestTokens.sign(claims().build(), TestTokens.generate()));
    }

    @Test
    void unsignedAlgNoneTokenReturns401() throws Exception {
        assertUnauthorized(new PlainJWT(claims().build()).serialize());
    }

    @Test
    void hs256TokenReturns401() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
        jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        assertUnauthorized(jwt.serialize());
    }

    private void assertUnauthorized(String token) throws Exception {
        ResponseEntity<String> response = get("/api/me", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(JSON.readTree(response.getBody()).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }
}
