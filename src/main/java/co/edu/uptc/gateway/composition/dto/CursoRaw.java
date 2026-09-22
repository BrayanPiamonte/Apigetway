package co.edu.uptc.gateway.composition.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Forma cruda de un curso según el módulo Materias (CursoResponse). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CursoRaw(
        Long id,
        Long materiaId,
        String materiaNombre,
        Long docenteId,
        String docenteNombre,
        String horario,
        String periodo,
        Integer cupo) {
}
