package co.edu.uptc.gateway.routing;

import co.edu.uptc.gateway.auth.TokenService;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Antes de reenviar al módulo: retira el token (el módulo no lo necesita), descarta cualquier X-User-* que
 * haya enviado el cliente y agrega la identidad ya verificada (X-User-Name / X-User-Role).
 */
@Component
public class IdentityHeadersFilter implements GlobalFilter, Ordered {

    static final String USER_NAME = "X-User-Name";
    static final String USER_ROLE = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .map(jwt -> withIdentity(exchange, jwt))
                .defaultIfEmpty(withIdentity(exchange, null))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove(USER_NAME);
                    headers.remove(USER_ROLE);
                    if (jwt != null) {
                        headers.set(USER_NAME, jwt.getSubject());
                        headers.set(USER_ROLE, jwt.getClaimAsString(TokenService.ROLE_CLAIM));
                    }
                }))
                .build();
    }

    @Override
    public int getOrder() {
        return -1; // antes de los filtros de enrutamiento (RouteToRequestUrl, Netty)
    }
}
