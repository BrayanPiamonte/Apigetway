package co.edu.uptc.gateway.routing;

import co.edu.uptc.gateway.config.GatewayProperties;
import co.edu.uptc.gateway.docs.OpenApiSpecAdapter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tabla de enrutamiento: prefijo de path -> módulo. Método, query y body se reenvían sin alterarse. */
@Configuration
public class RoutesConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties props, OpenApiSpecAdapter specs) {
        GatewayProperties.Services s = props.services();

        return builder.routes()
                // --- API de los módulos (protegida por SecurityConfig) ---
                .route("estudiantes", r -> r
                        .path("/api/estudiantes/**", "/api/programas/**", "/api/documentos/**")
                        .filters(RoutesConfig::dedupeCors)
                        .uri(s.estudiantes()))
                .route("materias", r -> r
                        .path("/api/materias/**")
                        .filters(RoutesConfig::dedupeCors)
                        .uri(s.materias()))
                .route("inscripciones", r -> r
                        .path("/api/inscripciones/**")
                        .filters(RoutesConfig::dedupeCors)
                        .uri(s.inscripciones()))

                // --- Contratos OpenAPI de los módulos, reescritos para ejecutarse a través del Gateway ---
                .route("docs-estudiantes", r -> r
                        .path("/docs/estudiantes.json")
                        .filters(f -> f.setPath("/api-docs.json")
                                .modifyResponseBody(String.class, String.class,
                                        (exchange, body) -> specs.adapt(body, "Módulo Estudiantes (vía Gateway)")))
                        .uri(s.estudiantes()))
                .route("docs-materias", r -> r
                        .path("/docs/materias.json")
                        .filters(f -> f.setPath("/v3/api-docs")
                                .modifyResponseBody(String.class, String.class,
                                        (exchange, body) -> specs.adapt(body, "Módulo Materias (vía Gateway)")))
                        .uri(s.materias()))
                .route("docs-inscripciones", r -> r
                        .path("/docs/inscripciones.json")
                        .filters(f -> f.setPath("/v3/api-docs")
                                .modifyResponseBody(String.class, String.class,
                                        (exchange, body) -> specs.adapt(body, "Módulo Inscripciones (vía Gateway)")))
                        .uri(s.inscripciones()))
                .build();
    }

    /** El Gateway ya responde CORS: se evitan cabeceras duplicadas si el módulo también las envía. */
    private static GatewayFilterSpec dedupeCors(GatewayFilterSpec f) {
        return f.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials", "RETAIN_FIRST");
    }
}
