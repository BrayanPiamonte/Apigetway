package co.edu.uptc.gateway.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Adapta el contrato OpenAPI de un módulo para ejecutarlo a través del Gateway:
 * servidor = el propio Gateway (así "Try it out" no salta la seguridad) y esquema Bearer global.
 */
@Component
public class OpenApiSpecAdapter {

    private final ObjectMapper mapper;

    public OpenApiSpecAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Mono<String> adapt(String body, String title) {
        if (body == null || body.isBlank()) {
            return Mono.empty();
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isObject() || !root.has("openapi")) {
                return Mono.just(body); // no es un contrato OpenAPI (p. ej. una respuesta de error): se deja igual
            }
            ObjectNode spec = (ObjectNode) root;

            objectAt(spec, "info").put("title", title);

            spec.putArray("servers").addObject().put("url", "/").put("description", "API Gateway");

            objectAt(objectAt(spec, "components"), "securitySchemes")
                    .putObject("bearerAuth")
                    .put("type", "http")
                    .put("scheme", "bearer")
                    .put("bearerFormat", "JWT");
            spec.putArray("security").addObject().putArray("bearerAuth");

            return Mono.just(mapper.writeValueAsString(spec));
        } catch (Exception e) {
            return Mono.just(body);
        }
    }

    private static ObjectNode objectAt(ObjectNode parent, String field) {
        JsonNode child = parent.get(field);
        return child instanceof ObjectNode node ? node : parent.putObject(field);
    }
}
