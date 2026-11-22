package co.edu.uptc.gateway.cache;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.edu.uptc.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Requisito 5.3 del Lab 4: cache-aside del catálogo de Materias. Redis se simula con un mapa en memoria;
 * la cadena simula al módulo Materias y cuenta cuántas veces se le reenvía la petición.
 */
class MateriasCacheFilterTest {

    private static final GatewayProperties PROPS = new GatewayProperties(
            new GatewayProperties.Jwt("01234567890123456789012345678901", "issuer", "audience"),
            new GatewayProperties.Services(
                    "http://localhost:1", "http://localhost:1", "http://localhost:1", "http://localhost:1"),
            List.of("*"), 1000, Duration.ofSeconds(5),
            new GatewayProperties.Cache(true, Duration.ofMinutes(2)));

    private static final String CATALOGO = "{\"content\":[{\"id\":1,\"nombre\":\"Sistemas Distribuidos\"}]}";

    private final Map<String, String> redisEnMemoria = new ConcurrentHashMap<>();
    private final AtomicInteger llamadasAlModulo = new AtomicInteger();
    private ReactiveValueOperations<String, String> ops;
    private MateriasCacheFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void redisFalso() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> Mono.justOrEmpty(redisEnMemoria.get(inv.<String>getArgument(0))));
        when(ops.set(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            redisEnMemoria.put(inv.getArgument(0), inv.getArgument(1));
            return Mono.just(true);
        });
        when(ops.increment(anyString())).thenAnswer(inv -> Mono.just(
                Long.parseLong(redisEnMemoria.merge(inv.getArgument(0), "1",
                        (viejo, uno) -> String.valueOf(Long.parseLong(viejo) + 1)))));
        filter = new MateriasCacheFilter(redis, PROPS);
    }

    @Test
    void primeraLecturaEsMissYLaSegundaHitSinLlamarAlModulo() {
        MockServerWebExchange primera = get("/api/materias?pageNumber=1&pageSize=5");
        filter.filter(primera, modulo(HttpStatus.OK, CATALOGO)).block();

        MockServerWebExchange segunda = get("/api/materias?pageNumber=1&pageSize=5");
        filter.filter(segunda, modulo(HttpStatus.OK, CATALOGO)).block();

        Assertions.assertEquals("MISS", cabecera(primera));
        Assertions.assertEquals("HIT", cabecera(segunda));
        Assertions.assertEquals("HIT", segunda.getResponse().getHeaders().getFirst(MateriasCacheFilter.X_CACHE));
        Assertions.assertEquals(1, llamadasAlModulo.get());
        Assertions.assertEquals(CATALOGO, segunda.getResponse().getBodyAsString().block());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, segunda.getResponse().getHeaders().getContentType());
    }

    @Test
    void laClaveIncluyeLosParametrosSinImportarSuOrden() {
        filter.filter(get("/api/materias?pageSize=5&pageNumber=1&sortBy=nombre"), modulo(HttpStatus.OK, CATALOGO)).block();

        MockServerWebExchange mismoOrdenDistinto = get("/api/materias?sortBy=nombre&pageNumber=1&pageSize=5");
        filter.filter(mismoOrdenDistinto, modulo(HttpStatus.OK, CATALOGO)).block();
        MockServerWebExchange otraPagina = get("/api/materias?sortBy=nombre&pageNumber=2&pageSize=5");
        filter.filter(otraPagina, modulo(HttpStatus.OK, CATALOGO)).block();

        Assertions.assertEquals("HIT", cabecera(mismoOrdenDistinto));
        Assertions.assertEquals("MISS", cabecera(otraPagina));
        Assertions.assertTrue(redisEnMemoria.containsKey(
                "cache:materias:v0:/api/materias?pageNumber=1&pageSize=5&sortBy=nombre"));
    }

    @Test
    void unaEscrituraExitosaInvalidaElCatalogo() {
        filter.filter(get("/api/materias?pageNumber=1"), modulo(HttpStatus.OK, CATALOGO)).block();

        MockServerWebExchange patch = MockServerWebExchange.from(MockServerHttpRequest.patch("/api/materias/1"));
        filter.filter(patch, modulo(HttpStatus.OK, "{}")).block();

        MockServerWebExchange despues = get("/api/materias?pageNumber=1");
        filter.filter(despues, modulo(HttpStatus.OK, CATALOGO)).block();

        Assertions.assertEquals("1", redisEnMemoria.get(MateriasCacheFilter.VERSION_KEY));
        Assertions.assertEquals("MISS", cabecera(despues));
    }

    @Test
    void unaEscrituraFallidaNoInvalida() {
        MockServerWebExchange post = MockServerWebExchange.from(MockServerHttpRequest.post("/api/materias"));
        filter.filter(post, modulo(HttpStatus.BAD_REQUEST, "{}")).block();

        verify(ops, never()).increment(anyString());
    }

    @Test
    void inscribirseTambienInvalidaPorqueCambiaLosCupos() {
        MockServerWebExchange inscripcion = MockServerWebExchange.from(MockServerHttpRequest.post("/api/inscripciones"));
        filter.filter(inscripcion, modulo(HttpStatus.CREATED, "{}")).block();

        Assertions.assertEquals("1", redisEnMemoria.get(MateriasCacheFilter.VERSION_KEY));
    }

    @Test
    void noGuardaRespuestasQueNoSean200() {
        MockServerWebExchange noExiste = get("/api/materias/999");
        filter.filter(noExiste, modulo(HttpStatus.NOT_FOUND, "{\"error\":\"no existe\"}")).block();

        Assertions.assertEquals("MISS", cabecera(noExiste));
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void lasUrlsFirmadasDeArchivosNoSeCachean() {
        MockServerWebExchange archivos = get("/api/materias/1/archivos/3/url");
        filter.filter(archivos, modulo(HttpStatus.OK, "{\"url\":\"https://firmada\"}")).block();

        Assertions.assertNull(cabecera(archivos));
        verifyNoInteractions(ops);
    }

    @Test
    void siRedisCaeLaPeticionSigueHaciaElModulo() {
        when(ops.get(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("Redis caído")));

        MockServerWebExchange exchange = get("/api/materias?pageNumber=1");
        filter.filter(exchange, modulo(HttpStatus.OK, CATALOGO)).block();

        Assertions.assertEquals("MISS", cabecera(exchange));
        Assertions.assertEquals(1, llamadasAlModulo.get());
        Assertions.assertEquals(CATALOGO, exchange.getResponse().getBodyAsString().block());
    }

    private static MockServerWebExchange get(String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri));
    }

    private static String cabecera(MockServerWebExchange exchange) {
        return exchange.getResponse().getHeaders().getFirst(MateriasCacheFilter.X_GATEWAY_CACHE);
    }

    /** Simula al módulo: responde con el estado y el cuerpo dados, y cuenta la llamada. */
    private GatewayFilterChain modulo(HttpStatus status, String cuerpo) {
        return exchange -> Mono.defer(() -> {
            llamadasAlModulo.incrementAndGet();
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response.writeWith(Mono.just(response.bufferFactory()
                    .wrap(cuerpo.getBytes(StandardCharsets.UTF_8))));
        });
    }
}
