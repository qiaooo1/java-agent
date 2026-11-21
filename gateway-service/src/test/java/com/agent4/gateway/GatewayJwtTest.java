package com.agent4.gateway;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GatewayJwtTest {
    private static final String SECRET = "gateway-isolation-test-secret-32-bytes";
    String sign(String subject, Instant expiry, String issuer, String secret) {
        var claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(Instant.now().minusSeconds(7200));
        if (subject != null) claims.subject(subject);
        if (expiry != null) claims.expiresAt(expiry);
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
    @Test void validTokenKeepsTheSignedSubject() {
        var decoder = new GatewayConfiguration().jwtDecoder(SECRET);
        assertEquals("user-a", decoder.decode(sign("user-a", Instant.now().plusSeconds(3600), "agent-workbench", SECRET)).block().getSubject());
    }
    @Test void missingIdentityOrExpiryAndInvalidCredentialsAreRejected() {
        var decoder = new GatewayConfiguration().jwtDecoder(SECRET); Instant future = Instant.now().plusSeconds(3600);
        for (String token : List.of(sign(null, future, "agent-workbench", SECRET), sign("", future, "agent-workbench", SECRET), sign("   ", future, "agent-workbench", SECRET), sign("user-a", null, "agent-workbench", SECRET), sign("user-a", Instant.now().minusSeconds(1200), "agent-workbench", SECRET), sign("user-a", future, "other-issuer", SECRET), sign("user-a", future, "agent-workbench", "another-isolation-test-secret-32-bytes"))) {
            assertThrows(JwtException.class, () -> decoder.decode(token).block());
        }
    }
    @Test void securityChainAuthenticatesBeforeTheHandlerAndSetsBrowserHeaders() {
        var configuration = new GatewayConfiguration();
        var http = org.springframework.security.config.web.server.ServerHttpSecurity.http()
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtDecoder(configuration.jwtDecoder(SECRET))));
        var chain = configuration.security(http);
        var proxy = new org.springframework.security.web.server.WebFilterChainProxy(chain);
        var anonymous = exchange("/api/tasks", null);
        run(proxy, anonymous); assertEquals(401, anonymous.getResponse().getStatusCode().value());
        assertTrue(anonymous.getResponse().getHeaders().getFirst("Cache-Control").contains("no-store"));
        var page = exchange("/", null); run(proxy, page);
        assertEquals(200, page.getResponse().getStatusCode().value());
        assertEquals("no-referrer", page.getResponse().getHeaders().getFirst("Referrer-Policy"));
        assertTrue(page.getResponse().getHeaders().getFirst("Content-Security-Policy").contains("connect-src 'self'"));
        var invalid = exchange("/api/tasks", sign("user-a", null, "agent-workbench", SECRET));
        run(proxy, invalid); assertEquals(401, invalid.getResponse().getStatusCode().value());
        var valid = exchange("/api/tasks", sign("user-a", Instant.now().plusSeconds(3600), "agent-workbench", SECRET));
        run(proxy, valid); assertEquals(200, valid.getResponse().getStatusCode().value());
        assertFalse(valid.getResponse().getHeaders().containsKey("Set-Cookie"));
        var nextWithoutToken = exchange("/api/tasks", null);
        run(proxy, nextWithoutToken); assertEquals(401, nextWithoutToken.getResponse().getStatusCode().value());
    }
    org.springframework.mock.web.server.MockServerWebExchange exchange(String path, String token) {
        var request = org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return org.springframework.mock.web.server.MockServerWebExchange.from(request.build());
    }
    void run(org.springframework.security.web.server.WebFilterChainProxy proxy, org.springframework.mock.web.server.MockServerWebExchange exchange) {
        proxy.filter(exchange, target -> {
            target.getResponse().setStatusCode(org.springframework.http.HttpStatus.OK);
            return target.getResponse().setComplete();
        }).block(java.time.Duration.ofSeconds(5));
    }

}
