package co.edu.uptc.gateway.cache;

import co.edu.uptc.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Cache-aside en Redis para las lecturas del catálogo de Materias (GET /api/materias/**).
 * <ul>
 *   <li>Clave: versión del catálogo + path + query params ordenados (paginación, orden y filtros).</li>
 *   <li>Cualquier escritura exitosa sobre Materias incrementa la versión: todo lo cacheado queda inválido.
 *       Se invalida el catálogo completo porque un cambio en una materia también altera los cursos que la muestran.</li>
 *   <li>Las URLs firmadas de archivos no se cachean: vencen antes o al ritmo del TTL.</li>
 *   <li>Si Redis falla o no responde, la petición sigue hacia el módulo sin caché (más lenta, nunca caída).</li>
 * </ul>
 */
@Component
public class MateriasCacheFilter implements GlobalFilter, Ordered {

    static final String PREFIX = "/api/materias";
    static final String INSCRIPCIONES_PREFIX = "/api/inscripciones";
    static final String X_CACHE = "X-Cache";
    /** Copia de X-Cache: CloudFront reemplaza X-Cache por la suya ("Miss from cloudfront") y esta llega intacta. */
    static final String X_GATEWAY_CACHE = "X-Gateway-Cache";
    static final String VERSION_KEY = "cache:materias:version";
    private static final String SEPARATOR = "\n";
    private static final Logger log = LoggerFactory.getLogger(MateriasCacheFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;

    public MateriasCacheFilter(ReactiveStringRedisTemplate redis, GatewayProperties props) {
        this.redis = redis;
        this.enabled = props.cache().enabled();
        this.ttl = props.cache().ttl();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        // Una inscripción (o su cancelación) cambia cuposDisponibles en Materias por la vía
        // interna, sin pasar por /api/materias: también debe invalidar el catálogo cacheado.
        if (enabled && path.startsWith(INSCRIPCIONES_PREFIX) && isWrite(method)) {
            return chain.filter(exchange).then(Mono.defer(() -> invalidateIfSuccessful(exchange)));
        }
        if (!enabled || !path.startsWith(PREFIX)) {
            return chain.filter(exchange);
        }
        if (HttpMethod.GET.equals(method)) {
            return isCacheable(path) ? cachedRead(exchange, chain, path) : chain.filter(exchange);
        }
        if (isWrite(method)) {
            return chain.filter(exchange).then(Mono.defer(() -> invalidateIfSuccessful(exchange)));
        }
        return chain.filter(exchange);
    }

    private static boolean isWrite(HttpMethod method) {
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method) || HttpMethod.DELETE.equals(method);
    }

    static boolean isCacheable(String path) {
        return !path.contains("/archivos");
    }

    private Mono<Void> cachedRead(ServerWebExchange exchange, GatewayFilterChain chain, String path) {
        String query = canonicalQuery(exchange.getRequest().getQueryParams());
        return currentVersion()
                .map(version -> "cache:materias:v" + version + ":" + path + "?" + query)
                .flatMap(key -> redis.opsForValue().get(key)
                        .map(entry -> new Lookup(key, entry))
                        .defaultIfEmpty(new Lookup(key, null)))
                .onErrorResume(e -> {
                    log.warn("Redis no disponible, se sigue sin caché: {}", e.toString());
                    return Mono.just(new Lookup(null, null));
                })
                .flatMap(lookup -> lookup.entry() != null
                        ? writeHit(exchange, lookup.entry())
                        : forwardAndStore(exchange, chain, lookup.key()));
    }

    private Mono<Long> currentVersion() {
        return redis.opsForValue().get(VERSION_KEY).map(Long::parseLong).defaultIfEmpty(0L);
    }

    static String canonicalQuery(MultiValueMap<String, String> params) {
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().stream().sorted().collect(Collectors.joining(",")))
                .collect(Collectors.joining("&"));
    }

    private Mono<Void> writeHit(ServerWebExchange exchange, String entry) {
        int split = entry.indexOf(SEPARATOR);
        String contentType = entry.substring(0, split);
        byte[] body = entry.substring(split + 1).getBytes(StandardCharsets.UTF_8);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().set(X_CACHE, "HIT");
        response.getHeaders().set(X_GATEWAY_CACHE, "HIT");
        if (!contentType.isEmpty()) {
            response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        }
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** MISS: se reenvía al módulo y, si responde 200, se guarda el cuerpo en Redis con el TTL configurado. */
    private Mono<Void> forwardAndStore(ServerWebExchange exchange, GatewayFilterChain chain, String key) {
        ServerHttpResponse original = exchange.getResponse();
        original.getHeaders().set(X_CACHE, "MISS");
        original.getHeaders().set(X_GATEWAY_CACHE, "MISS");
        if (key == null) {
            return chain.filter(exchange);
        }
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (!HttpStatus.OK.equals(getStatusCode())) {
                    return super.writeWith(body);
                }
                return DataBufferUtils.join(body).flatMap(joined -> {
                    byte[] bytes = new byte[joined.readableByteCount()];
                    joined.read(bytes);
                    DataBufferUtils.release(joined);
                    store(key, getHeaders(), bytes);
                    return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
                });
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    private void store(String key, HttpHeaders headers, byte[] body) {
        MediaType type = headers.getContentType();
        String entry = (type != null ? type.toString() : "") + SEPARATOR + new String(body, StandardCharsets.UTF_8);
        redis.opsForValue().set(key, entry, ttl)
                .doOnError(e -> log.warn("No se pudo guardar en Redis: {}", e.toString()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private Mono<Void> invalidateIfSuccessful(ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value() : 0);
        if (status == null || !status.is2xxSuccessful()) {
            return Mono.empty();
        }
        return redis.opsForValue().increment(VERSION_KEY)
                .doOnNext(v -> log.info("Catálogo de Materias invalidado (versión {})", v))
                .onErrorResume(e -> {
                    log.warn("No se pudo invalidar el caché en Redis: {}", e.toString());
                    return Mono.empty();
                })
                .then();
    }

    @Override
    public int getOrder() {
        return -2; // antes de NettyWriteResponseFilter (-1) para poder leer el cuerpo de la respuesta
    }

    private record Lookup(String key, String entry) {
    }
}
