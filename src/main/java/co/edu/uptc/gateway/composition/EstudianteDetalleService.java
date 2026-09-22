package co.edu.uptc.gateway.composition;

import co.edu.uptc.gateway.composition.dto.CursoRaw;
import co.edu.uptc.gateway.composition.dto.DocenteRaw;
import co.edu.uptc.gateway.composition.dto.InscripcionRaw;
import co.edu.uptc.gateway.composition.dto.PageRaw;
import co.edu.uptc.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Orquesta GET /api/estudiantes/{id}/detalle (sección 6.1 del enunciado): junta en un solo JSON
 * lo que hoy vive repartido en los tres módulos, siguiendo los 5 pasos del documento.
 * <p>
 * Si cualquier módulo falla (caído, lento o responde un error), toda la operación falla con un
 * error controlado en vez de devolver un JSON a medias: así queda claro, al sustentar, qué pieza
 * faltó y por qué.
 */
@Service
public class EstudianteDetalleService {

    /** El módulo Inscripciones solo admite pageSize 10, 20 o 50; se usa el más grande para minimizar llamadas. */
    private static final int PAGE_SIZE = 50;

    private final WebClient client;
    private final String estudiantesBase;
    private final String materiasBase;
    private final String inscripcionesBase;

    public EstudianteDetalleService(WebClient compositionWebClient, GatewayProperties props) {
        this.client = compositionWebClient;
        this.estudiantesBase = props.services().estudiantes();
        this.materiasBase = props.services().materias();
        this.inscripcionesBase = props.services().inscripciones();
    }

    public Mono<EstudianteDetalleResponse> obtener(Long id) {
        Mono<JsonNode> estudiante = fetchEstudiante(id); // paso 1
        Mono<List<InscripcionDetalle>> inscripciones = fetchTodasLasInscripciones(id) // paso 2
                .flatMap(this::enriquecerConCursosYDocentes); // pasos 3-4
        return Mono.zip(estudiante, inscripciones) // paso 5: ensamblar
                .map(tuple -> new EstudianteDetalleResponse(tuple.getT1(), tuple.getT2()));
    }

    // ---- Paso 1: GET Estudiante/{id} ----

    private Mono<JsonNode> fetchEstudiante(Long id) {
        return client.get()
                .uri(estudiantesBase + "/api/estudiantes/{id}", id)
                .retrieve()
                .onStatus(status -> status.value() == 404, response -> Mono.error(new StudentNotFoundException(id)))
                .onStatus(HttpStatusCode::isError,
                        response -> Mono.error(new CompositionUpstreamException("estudiantes", response.statusCode())))
                .bodyToMono(JsonNode.class);
    }

    // ---- Paso 2: GET Inscripciones?estudianteId={id} (todas las páginas) ----

    private Mono<List<InscripcionRaw>> fetchTodasLasInscripciones(Long estudianteId) {
        return fetchInscripcionesPage(estudianteId, 0).flatMap(primera -> {
            if (primera.totalPages() <= 1) {
                return Mono.just(primera.data());
            }
            return Flux.range(1, primera.totalPages() - 1)
                    .flatMap(pagina -> fetchInscripcionesPage(estudianteId, pagina))
                    .map(PageRaw::data)
                    .reduce(new ArrayList<>(primera.data()), (acumulado, siguiente) -> {
                        acumulado.addAll(siguiente);
                        return acumulado;
                    })
                    .map(List::copyOf);
        });
    }

    private Mono<PageRaw<InscripcionRaw>> fetchInscripcionesPage(Long estudianteId, int pageNumber) {
        return client.get()
                .uri(inscripcionesBase + "/api/inscripciones?estudianteId={id}&pageNumber={p}&pageSize={s}",
                        estudianteId, pageNumber, PAGE_SIZE)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> Mono.error(new CompositionUpstreamException("inscripciones", response.statusCode())))
                .bodyToMono(new ParameterizedTypeReference<PageRaw<InscripcionRaw>>() {
                });
    }

    // ---- Pasos 3-4: GET Curso/{cursoId} y GET Docente/{docenteId}, sin repetir llamadas ----

    private Mono<List<InscripcionDetalle>> enriquecerConCursosYDocentes(List<InscripcionRaw> inscripciones) {
        List<Long> cursoIds = inscripciones.stream().map(InscripcionRaw::cursoId).distinct().toList();

        return Flux.fromIterable(cursoIds)
                .flatMap(this::fetchCurso)
                .collectMap(CursoRaw::id)
                .flatMap(cursosPorId -> {
                    List<Long> docenteIds = cursosPorId.values().stream()
                            .map(CursoRaw::docenteId).distinct().toList();
                    return Flux.fromIterable(docenteIds)
                            .flatMap(this::fetchDocente)
                            .collectMap(DocenteRaw::id)
                            .map(docentesPorId -> inscripciones.stream()
                                    .map(insc -> toDetalle(insc, cursosPorId.get(insc.cursoId()), docentesPorId))
                                    .toList());
                });
    }

    private Mono<CursoRaw> fetchCurso(Long cursoId) {
        return client.get()
                .uri(materiasBase + "/api/materias/cursos/{id}", cursoId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(
                        new CompositionUpstreamException("materias (curso " + cursoId + ")", response.statusCode())))
                .bodyToMono(CursoRaw.class);
    }

    private Mono<DocenteRaw> fetchDocente(Long docenteId) {
        return client.get()
                .uri(materiasBase + "/api/materias/docentes/{id}", docenteId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(
                        new CompositionUpstreamException("materias (docente " + docenteId + ")", response.statusCode())))
                .bodyToMono(DocenteRaw.class);
    }

    private static InscripcionDetalle toDetalle(InscripcionRaw insc, CursoRaw curso, Map<Long, DocenteRaw> docentesPorId) {
        DocenteRaw docente = curso == null ? null : docentesPorId.get(curso.docenteId());
        CursoDetalle cursoDetalle = curso == null ? null : new CursoDetalle(
                curso.id(), curso.materiaNombre(), curso.horario(), curso.periodo(),
                docente == null ? null : new DocenteDetalle(docente.id(), docente.nombreCompleto()));
        List<NotaDetalle> notas = insc.notas() == null ? List.of()
                : insc.notas().stream().map(n -> new NotaDetalle(n.tipo(), n.valor())).toList();
        return new InscripcionDetalle(insc.idInscripcion(), insc.periodo(), insc.estado(), cursoDetalle, notas);
    }
}
