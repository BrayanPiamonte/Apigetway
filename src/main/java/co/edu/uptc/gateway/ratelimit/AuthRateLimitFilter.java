package co.edu.uptc.gateway.ratelimit;

import co.edu.uptc.gateway.config.GatewayProperties;
import co.edu.uptc.gateway.error.JsonErrors;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Freno a fuerza bruta: máximo N intentos por minuto y por IP en /auth/login y /auth/register. */
@Component
@Order(-200) // antes de la cadena de Spring Security
public class AuthRateLimitFilter implements WebFilter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_IPS = 10_000;

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }

    private final int limit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(GatewayProperties props) {
        this.limit = props.rateLimitPerMinute();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        boolean guarded = HttpMethod.POST.equals(request.getMethod())
                && (path.equals("/auth/login") || path.equals("/auth/register"));
        if (!guarded) {
            return chain.filter(exchange);
        }

        long now = System.currentTimeMillis();
        Window window = windows.compute(clientIp(request),
                (ip, current) -> current == null || now - current.start >= WINDOW_MILLIS ? new Window(now) : current);
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.values().removeIf(w -> now - w.start >= WINDOW_MILLIS);
        }

        if (window.count.incrementAndGet() > limit) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
            return JsonErrors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Demasiados intentos, espera un minuto");
        }
        return chain.filter(exchange);
    }

    private static String clientIp(ServerHttpRequest request) {
        InetSocketAddress address = request.getRemoteAddress();
        return address != null && address.getAddress() != null ? address.getAddress().getHostAddress() : "desconocida";
    }
}
