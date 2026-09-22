package co.edu.uptc.gateway.composition;

import io.swagger.v3.oas.annotations.media.Schema;

public record DocenteDetalle(
        @Schema(example = "3") Long id,
        @Schema(example = "Andrés Vargas") String nombre) {
}
