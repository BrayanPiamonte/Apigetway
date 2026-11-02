package co.edu.uptc.gateway;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * El Gateway ya no emite tokens (los firma el módulo de Autenticación). Para probar su validación,
 * los tests fabrican JWT con la misma clave, emisor y audiencia que usa la configuración de prueba.
 */
final class TestTokens {

    static final String SECRET = "0123456789-0123456789-0123456789-0123";
    static final String ISSUER = "api-gateway-uptc";
    static final String AUDIENCE = "sistema-academico";

    private TestTokens() {
    }

    static String mint(String username, String role) {
        Instant now = Instant.now();
        return mint(username, role, SECRET, now, now.plus(Duration.ofHours(1)));
    }

    static String mint(String username, String role, String secret, Instant issuedAt, Instant expiresAt) {
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(username)
                .audience(List.of(AUDIENCE))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", role)
                .build();
        return new NimbusJwtEncoder(jwks)
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
