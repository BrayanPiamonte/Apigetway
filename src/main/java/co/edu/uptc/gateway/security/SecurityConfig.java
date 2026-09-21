package co.edu.uptc.gateway.security;

import co.edu.uptc.gateway.auth.Role;
import co.edu.uptc.gateway.auth.TokenService;
import co.edu.uptc.gateway.config.GatewayProperties;
import co.edu.uptc.gateway.error.JsonErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Seguridad centralizada en el Gateway. Los módulos no validan tokens: solo se llega a ellos pasando por aquí.
 * <ul>
 *   <li>Públicos: login, registro, health y documentación.</li>
 *   <li>/api/**: JWT válido. GET/HEAD para ADMIN y USER; cualquier otro método solo ADMIN.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/auth/login", "/auth/register", "/health",
            "/swagger-ui.html", "/swagger-ui/**", "/webjars/**", "/v3/api-docs/**", "/docs/**"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {

        ServerAuthenticationEntryPoint unauthorized = (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"api-gateway\"");
            String reason = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (ex instanceof OAuth2AuthenticationException) {
                boolean expired = reason.contains("expired");
                return JsonErrors.write(exchange, HttpStatus.UNAUTHORIZED,
                        expired ? "TOKEN_EXPIRED" : "TOKEN_INVALID",
                        expired ? "El token expiró, inicia sesión de nuevo" : "Token inválido");
            }
            return JsonErrors.write(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_REQUIRED",
                    "Falta el token: Authorization: Bearer <jwt>");
        };

        ServerAccessDeniedHandler forbidden = (exchange, ex) -> JsonErrors.write(exchange, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Tu rol no permite esta operación (el rol USER solo puede consultar)");

        // En rutas públicas se ignora el Authorization: un token vencido no debe impedir hacer login.
        ServerWebExchangeMatcher publicPaths = ServerWebExchangeMatchers.pathMatchers(PUBLIC_PATHS);
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(Customizer.withDefaults())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance()) // sin sesión
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .pathMatchers("/auth/me").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/**").hasAnyRole(Role.ADMIN.name(), Role.USER.name())
                        .pathMatchers(HttpMethod.HEAD, "/api/**").hasAnyRole(Role.ADMIN.name(), Role.USER.name())
                        .pathMatchers("/api/**").hasRole(Role.ADMIN.name())
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .bearerTokenConverter(exchange -> publicPaths.matches(exchange)
                                .flatMap(match -> match.isMatch()
                                        ? Mono.<Authentication>empty()
                                        : bearer.convert(exchange)))
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(rolesConverter()))))
                .build();
    }

    /** Claim "role": "ADMIN" -> autoridad ROLE_ADMIN. */
    private static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(props.corsOrigins());
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
