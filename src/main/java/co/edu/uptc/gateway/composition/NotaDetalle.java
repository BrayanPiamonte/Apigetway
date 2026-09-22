package co.edu.uptc.gateway.composition;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotaDetalle(
        @Schema(example = "Parcial 1") String tipo,
        @Schema(example = "4.2") Double valor) {
}
