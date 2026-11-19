package co.edu.uptc.gateway.inscripciones;

import co.edu.uptc.gateway.error.ApiError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Valida el programa académico antes de reenviar el alta administrativa
 * POST /api/inscripciones al módulo Inscripciones.
 *
 * <p>Se implementa como filtro global y no como controlador para no tapar el
 * enrutamiento del resto de métodos (GET, PUT, PATCH, DELETE) sobre la misma
 * ruta: el Gateway sigue siendo un proxy transparente para ellos.</p>
 */
@Component
public class InscripcionProgramaFilter implements GlobalFilter, Ordered {

    private static final String RUTA = "/api/inscripciones";

    private final ProgramaCompatibilityService compatibility;
    private final ObjectMapper mapper;

    public InscripcionProgramaFilter(ProgramaCompatibilityService compatibility, ObjectMapper mapper) {
        this.compatibility = compatibility;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.POST.equals(request.getMethod()) || !RUTA.equals(request.getPath().value())) {
            return chain.filter(exchange);
        }

        // Ojo: no usar switchIfEmpty sobre el resultado de validarYContinuar, porque un
        // Mono<Void> completa vacío y volvería a encadenar el proxy tras rechazar la
        // petición (el cuerpo ya está consumido y la petición queda colgada hasta el
        // timeout de 10 s). El cuerpo vacío se resuelve antes del flatMap.
        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(buffer -> validarYContinuar(exchange, chain, buffer));
    }

    private Mono<Void> validarYContinuar(ServerWebExchange exchange, GatewayFilterChain chain, DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);

        JsonNode cuerpo = leer(bytes);
        if (cuerpo == null) {
            return chain.filter(conCuerpo(exchange, bytes));
        }

        JsonNode estudiante = cuerpo.get("estudianteId");
        JsonNode curso = cuerpo.get("cursoId");
        if (estudiante == null || curso == null || !estudiante.canConvertToLong() || !curso.canConvertToLong()) {
            // El módulo Inscripciones devuelve el 422 de validación correspondiente.
            return chain.filter(conCuerpo(exchange, bytes));
        }

        ServerWebExchange continuar = conCuerpo(exchange, bytes);
        return compatibility.validar(estudiante.asLong(), curso.asLong())
                .then(Mono.defer(() -> chain.filter(continuar)))
                .onErrorResume(ProgramaIncompatibleException.class, e -> rechazar(
                        exchange, HttpStatus.UNPROCESSABLE_ENTITY, "PROGRAMA_INCOMPATIBLE", e.getMessage()))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> rechazar(
                        exchange, HttpStatus.NOT_FOUND, "INSCRIPCION_NO_ENCONTRADA",
                        "El estudiante o el curso indicado no existe"))
                .onErrorResume(WebClientResponseException.class, e -> rechazar(
                        exchange, HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                        "No se pudo validar el programa del curso en los módulos"));
    }

    /** Reinyecta el cuerpo consumido para que el filtro de enrutamiento lo reenvíe intacto. */
    private ServerWebExchange conCuerpo(ServerWebExchange exchange, byte[] bytes) {
        ServerHttpRequest original = exchange.getRequest();
        ServerHttpRequest decorado = new ServerHttpRequestDecorator(original) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
            }
        };
        return exchange.mutate().request(decorado).build();
    }

    private JsonNode leer(byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    private Mono<Void> rechazar(ServerWebExchange exchange, HttpStatus status, String codigo, String mensaje) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] cuerpo;
        try {
            cuerpo = mapper.writeValueAsBytes(ApiError.of(codigo, mensaje));
        } catch (JsonProcessingException e) {
            cuerpo = ("{\"error\":{\"code\":\"" + codigo + "\"}}").getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(cuerpo)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
