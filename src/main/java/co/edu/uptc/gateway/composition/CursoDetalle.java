package co.edu.uptc.gateway.composition;

import io.swagger.v3.oas.annotations.media.Schema;

public record CursoDetalle(
        @Schema(example = "7") Long id,
        @Schema(example = "Sistemas Distribuidos", description = "Nombre de la materia del curso") String nombre,
        @Schema(example = "5", description = "Semestre del pensum de la materia") Integer semestre,
        @Schema(example = "ISIS", description = "Código del programa de la materia") String programaCodigo,
        @Schema(example = "Lunes-Miércoles 8:00-10:00") String horario,
        @Schema(example = "2026-2") String periodo,
        @Schema(example = "ACTIVO", description = "ACTIVO o INACTIVO (un curso no se borra, se inactiva)") String estado,
        @Schema(description = "null si el docente no estuvo disponible") DocenteDetalle docente) {
}
