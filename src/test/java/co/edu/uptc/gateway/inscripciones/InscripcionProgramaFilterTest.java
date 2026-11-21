package co.edu.uptc.gateway.inscripciones;

import co.edu.uptc.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Regresión del 504 fantasma: al rechazar una inscripción el filtro no debe
 * encadenar el proxy (el cuerpo ya está consumido y la petición colgaba hasta
 * el timeout de 10 s), y al aceptarla debe reenviarla exactamente una vez.
 */
class InscripcionProgramaFilterTest {

    private static final GatewayProperties PROPS = new GatewayProperties(
            new GatewayProperties.Jwt("01234567890123456789012345678901", "issuer", "audience"),
            new GatewayProperties.Services(
                    "http://localhost:1", "http://localhost:1", "http://localhost:1", "http://localhost:1"),
            List.of("*"), 1000, Duration.ofSeconds(5),
            new GatewayProperties.Cache(false, Duration.ofMinutes(2)));

    private final AtomicInteger reenvios = new AtomicInteger();
    private final StringBuilder cuerpoReenviado = new StringBuilder();

    @Test
    void rechazaSinReenviarAlModulo() {
        InscripcionProgramaFilter filter = filtro(Mono.error(new ProgramaIncompatibleException("programa distinto")));
        MockServerWebExchange exchange = intercambio(1, 2001);

        filter.filter(exchange, cadena()).block();

        Assertions.assertEquals(0, reenvios.get());
        String cuerpo = exchange.getResponse().getBodyAsString().block();
        Assertions.assertNotNull(cuerpo);
        Assertions.assertTrue(cuerpo.contains("PROGRAMA_INCOMPATIBLE"));
    }

    @Test
    void reenviaUnaSolaVezCuandoEsCompatible() {
        InscripcionProgramaFilter filter = filtro(Mono.empty());
        MockServerWebExchange exchange = intercambio(1, 1973);

        filter.filter(exchange, cadena()).block();

        Assertions.assertEquals(1, reenvios.get());
        Assertions.assertTrue(cuerpoReenviado.toString().contains("\"cursoId\":1973"));
    }

    private InscripcionProgramaFilter filtro(Mono<Void> resultadoValidacion) {
        return new InscripcionProgramaFilter(new CompatibilidadFalsa(resultadoValidacion), new ObjectMapper());
    }

    private GatewayFilterChain cadena() {
        return exchange -> Mono.defer(() -> {
            reenvios.incrementAndGet();
            return copiarCuerpo(exchange);
        });
    }

    private Mono<Void> copiarCuerpo(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    cuerpoReenviado.append(new String(bytes, StandardCharsets.UTF_8));
                })
                .then();
    }

    private MockServerWebExchange intercambio(long estudianteId, long cursoId) {
        String cuerpo = "{\"estudianteId\":" + estudianteId + ",\"cursoId\":" + cursoId
                + ",\"periodo\":\"2026-2\"}";
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/inscripciones")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo));
    }

    private static final class CompatibilidadFalsa extends ProgramaCompatibilityService {

        private final Mono<Void> resultado;

        private CompatibilidadFalsa(Mono<Void> resultado) {
            super(WebClient.builder().build(), PROPS);
            this.resultado = resultado;
        }

        @Override
        public Mono<Void> validar(Long estudianteId, Long cursoId) {
            return resultado;
        }
    }
}
