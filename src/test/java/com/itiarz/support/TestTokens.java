package com.itiarz.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Signs Keycloak-shaped access tokens with a per-JVM RSA key that the test JwtDecoder trusts. */
public final class TestTokens {

    public static final String ISSUER = "http://localhost:8081/realms/itiarz";
    public static final String AUDIENCE = "itiarz-api";

    private static final KeyPair KEYS = generate();

    private TestTokens() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    public static String jwt(String sub, String email, List<String> orgs, boolean platformAdmin) {
        return jwt(sub, email, orgs, platformAdmin, List.of(AUDIENCE, "account"));
    }

    public static String jwt(String sub, String email, List<String> orgs, boolean platformAdmin,
                             List<String> audience) {
        return sign(claims(sub, email, orgs, platformAdmin).audience(audience).build());
    }

    /** Keycloak-shaped claims (valid issuer, audience, 5 min lifetime) for tests that tweak single claims. */
    public static JWTClaimsSet.Builder claims(String sub, String email, List<String> orgs, boolean platformAdmin) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(sub)
                .audience(List.of(AUDIENCE, "account"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Test " + email)
                .claim("realm_access", Map.of("roles",
                        platformAdmin ? List.of("default-roles-itiarz", "platform-admin")
                                : List.of("default-roles-itiarz")));
        if (orgs != null && !orgs.isEmpty()) {
            claims.claim("organization", orgs);
        }
        return claims;
    }

    /** Signs with the trusted test key (RS256). */
    public static String sign(JWTClaimsSet claims) {
        return sign(claims, KEYS);
    }

    /** Signs with an arbitrary RSA key pair (e.g. an untrusted one). */
    public static String sign(JWTClaimsSet claims, KeyPair keys) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
