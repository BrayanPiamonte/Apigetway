package co.edu.uptc.gateway.composition.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Forma cruda de una inscripción según el módulo Inscripciones (InscripcionResponseDTO).
 * Se ignora "historial": el contrato de /detalle (sección 6.1 del enunciado) no lo pide.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InscripcionRaw(
        Long idInscripcion,
        Long estudianteId,
        Long cursoId,
        String periodo,
        String estado,
        String fechaInscripcion,
        List<NotaRaw> notas) {
}
