package co.edu.uptc.gateway.composition;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta de GET /api/estudiantes/{id}/detalle.
 * "estudiante" viaja tal cual lo devuelve el módulo Estudiantes, sin agregarle nada.
 * "inscripciones" es la lista de inscripciones, o el texto "NO_DISPONIBLE" si el módulo
 * Inscripciones falló. "parcial" es true cuando falta algún dato (B4).
 */
public record EstudianteDetalleResponse(
        @Schema(description = "Tal cual lo devuelve el módulo Estudiantes, sin cambios") JsonNode estudiante,
        @Schema(description = "Lista de InscripcionDetalle, o \"NO_DISPONIBLE\" si el módulo Inscripciones falló",
                oneOf = {InscripcionDetalle[].class, String.class}) Object inscripciones,
        @Schema(description = "true si algún módulo o dato no estuvo disponible", example = "false") boolean parcial) {
}
