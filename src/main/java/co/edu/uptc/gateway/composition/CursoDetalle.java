package co.edu.uptc.gateway.composition;

import io.swagger.v3.oas.annotations.media.Schema;

public record CursoDetalle(
        @Schema(example = "7") Long id,
        @Schema(example = "Sistemas Distribuidos", description = "Nombre de la materia del curso") String nombre,
        @Schema(example = "Lunes-Miércoles 8:00-10:00") String horario,
        @Schema(example = "2026-2") String periodo,
        DocenteDetalle docente) {
}
