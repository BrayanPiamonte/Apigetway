package co.edu.uptc.gateway.composition;

import co.edu.uptc.gateway.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Endpoint de composición (BFF) del Gateway — sección 6.1 del enunciado. A diferencia del
 * enrutamiento simple (que es solo un proxy sin lógica propia), esta ruta sí orquesta llamadas
 * a los tres módulos, por lo que el propio Gateway la documenta aquí con Swagger.
 * <p>
 * Se registra como un @RestController normal en vez de como ruta del Gateway: en Spring WebFlux,
 * las rutas de {@code @RestController} (RequestMappingHandlerMapping, orden 0) se resuelven antes
 * que las del Gateway (RoutePredicateHandlerMapping, orden 1), así que esta única ruta intercepta
 * antes de que "/api/estudiantes/**" la reenvíe sin más al módulo Estudiantes.
 */
@RestController
@Tag(name = "Composición (BFF)", description = "Lógica propia del Gateway: junta datos de los tres módulos")
public class CompositionController {

    private final EstudianteDetalleService service;

    public CompositionController(EstudianteDetalleService service) {
        this.service = service;
    }

    @GetMapping("/api/estudiantes/{id}/detalle")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Detalle compuesto de un estudiante (BFF)",
            description = """
                    Orquesta, en este orden, llamadas a los tres módulos y devuelve un solo JSON:
                    1. GET Estudiante/{id} al módulo Estudiantes.
                    2. GET Inscripciones?estudianteId={id} al módulo Inscripciones (ya trae las notas de cada inscripción).
                    3. Para cada inscripción, GET Curso/{cursoId} al módulo Materias.
                    4. Para cada curso, GET Docente/{docenteId} al módulo Materias.
                    5. El Gateway ensambla todo en la respuesta.

                    Si algún módulo falla (caído, lento, o responde un error) en cualquiera de estos
                    pasos, toda la operación falla con un error controlado: no se devuelve un JSON
                    a medias. `curso` y `cursoIds`/`docenteIds` repetidos entre inscripciones se
                    piden una sola vez.""")
    @ApiResponse(responseCode = "200", description = "Detalle compuesto",
            content = @Content(schema = @Schema(implementation = EstudianteDetalleResponse.class)))
    @ApiResponse(responseCode = "401", description = "Token ausente, inválido o expirado",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No existe un estudiante con ese id",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "Un módulo respondió un error al construir el detalle",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Un módulo no está disponible",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "504", description = "Un módulo tardó demasiado en responder",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public Mono<EstudianteDetalleResponse> detalle(
            @Parameter(description = "Id del estudiante", example = "12")
            @PathVariable("id") Long id) {
        return service.obtener(id);
    }
}
