package co.edu.uptc.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Escribe errores JSON desde filtros y handlers de bajo nivel (donde no hay @RestController). */
public final class JsonErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonErrors() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(ApiError.of(code, message));
        } catch (JsonProcessingException e) {
            bytes = ("{\"error\":{\"code\":\"" + code + "\"}}").getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
