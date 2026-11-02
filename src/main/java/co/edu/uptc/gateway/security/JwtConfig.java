package co.edu.uptc.gateway.security;

import co.edu.uptc.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Verificación (cada petición a /api/**) de los JWT. El Gateway ya NO emite tokens: los firma el módulo de
 * Autenticación con la misma clave HS256 (variable JWT_SECRET compartida).
 */
@Configuration
public class JwtConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(GatewayProperties props) {
        GatewayProperties.Jwt jwt = props.jwt();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(secretKey(props))
                .macAlgorithm(MacAlgorithm.HS256) // algoritmo fijo: evita "alg: none" y confusión HS/RS
                .build();

        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(jwt.audience()));
        // createDefaultWithIssuer = firma vigente (exp/nbf) + emisor esperado
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.issuer()), audience));
        return decoder;
    }

    private static SecretKey secretKey(GatewayProperties props) {
        return new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
