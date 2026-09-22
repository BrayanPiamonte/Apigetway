package co.edu.uptc.gateway.composition;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Respuesta de GET /api/estudiantes/{id}/detalle (sección 6.1 del enunciado).
 * "estudiante" viaja tal cual lo devuelve el módulo Estudiantes, sin agregarle nada.
 */
public record EstudianteDetalleResponse(
        @Schema(description = "Tal cual lo devuelve el módulo Estudiantes, sin cambios") JsonNode estudiante,
        List<InscripcionDetalle> inscripciones) {
}
