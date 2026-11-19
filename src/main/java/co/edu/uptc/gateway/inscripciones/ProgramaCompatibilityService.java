package co.edu.uptc.gateway.inscripciones;

import co.edu.uptc.gateway.composition.dto.CursoRaw;
import co.edu.uptc.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Comprueba que un estudiante solo se inscriba en materias de su programa.
 *
 * <p>El dato del programa vive en dos módulos distintos: Estudiantes expone
 * {@code programaCodigo} en GET /api/estudiantes/{id} y Materias lo expone en
 * cada curso (GET /api/materias/cursos/{id}). El Gateway cruza ambos antes de
 * reenviar la inscripción al módulo Inscripciones, que no conoce programas.</p>
 */
@Service
public class ProgramaCompatibilityService {

    private final WebClient client;
    private final String estudiantesBase;
    private final String materiasBase;

    public ProgramaCompatibilityService(WebClient compositionWebClient, GatewayProperties props) {
        this.client = compositionWebClient;
        this.estudiantesBase = props.services().estudiantes();
        this.materiasBase = props.services().materias();
    }

    /** Completa vacío si son compatibles; falla con {@link ProgramaIncompatibleException} si no. */
    public Mono<Void> validar(Long estudianteId, Long cursoId) {
        Mono<JsonNode> estudiante = client.get()
                .uri(estudiantesBase + "/api/estudiantes/{id}", estudianteId)
                .retrieve()
                .bodyToMono(JsonNode.class);

        Mono<CursoRaw> curso = client.get()
                .uri(materiasBase + "/api/materias/cursos/{id}", cursoId)
                .retrieve()
                .bodyToMono(CursoRaw.class);

        return Mono.zip(estudiante, curso).flatMap(tuple -> {
            String programaEstudiante = tuple.getT1().path("programaCodigo").asText(null);
            CursoRaw cursoRaw = tuple.getT2();
            String programaCurso = cursoRaw.programaCodigo();

            // Datos antiguos sin código de programa: no se bloquea la inscripción.
            if (esVacio(programaEstudiante) || esVacio(programaCurso)) {
                return Mono.empty();
            }
            if (!programaEstudiante.equalsIgnoreCase(programaCurso)) {
                String materia = cursoRaw.materiaNombre() == null ? "seleccionada" : cursoRaw.materiaNombre();
                return Mono.error(new ProgramaIncompatibleException(
                        "La asignatura " + materia + " pertenece al programa " + programaCurso
                                + ", pero el estudiante está matriculado en " + programaEstudiante));
            }
            return Mono.empty();
        });
    }

    private static boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
