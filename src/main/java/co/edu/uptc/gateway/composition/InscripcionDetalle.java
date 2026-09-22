package co.edu.uptc.gateway.composition;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record InscripcionDetalle(
        @Schema(example = "45") Long id,
        @Schema(example = "2026-2") String periodo,
        @Schema(example = "ACTIVA", allowableValues = {"ACTIVA", "RETIRADA", "FINALIZADA", "CANCELADA"})
        String estado,
        CursoDetalle curso,
        List<NotaDetalle> notas) {
}
