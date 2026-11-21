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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Orquesta GET /api/estudiantes/{id}/detalle juntando lo que vive en los tres módulos.
 * <p>
 * B4 · /detalle que no se rompe:
 * <ul>
 *   <li>El estudiante es obligatorio: si no existe, 404; si Estudiantes no responde, 503.</li>
 *   <li>Si Inscripciones falla, {@code inscripciones = "NO_DISPONIBLE"} y {@code parcial = true}.</li>
 *   <li>Si un curso o un docente falla o ya no existe, ese campo va en {@code null} y
 *       {@code parcial = true}. Nunca se responde 500 por un dato que falta.</li>
 * </ul>
 */
@Service
public class EstudianteDetalleService {

    private static final Logger log = LoggerFactory.getLogger(EstudianteDetalleService.class);

    /** El módulo Inscripciones solo admite pageSize 10, 20 o 50; se usa el más grande para minimizar llamadas. */
    private static final int PAGE_SIZE = 50;

    static final String NO_DISPONIBLE = "NO_DISPONIBLE";

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
        AtomicBoolean parcial = new AtomicBoolean(false);

        Mono<JsonNode> estudiante = fetchEstudiante(id); // paso 1 (obligatorio)
        Mono<Object> inscripciones = fetchTodasLasInscripciones(id) // paso 2
                .flatMap(lista -> enriquecerConCursosYDocentes(lista, parcial)) // pasos 3-4
                .<Object>map(lista -> lista)
                .onErrorResume(e -> {
                    log.warn("/detalle {}: Inscripciones no disponible ({})", id, e.toString());
                    parcial.set(true);
                    return Mono.just(NO_DISPONIBLE);
                });

        return Mono.zip(estudiante, inscripciones) // paso 5: ensamblar
                .map(t -> new EstudianteDetalleResponse(t.getT1(), t.getT2(), parcial.get()));
    }

    // ---- Paso 1: GET Estudiante/{id} (obligatorio) ----

    private Mono<JsonNode> fetchEstudiante(Long id) {
        return client.get()
                .uri(estudiantesBase + "/api/estudiantes/{id}", id)
                .retrieve()
                .onStatus(status -> status.value() == 404, response -> Mono.error(new StudentNotFoundException(id)))
                .onStatus(HttpStatusCode::isError,
                        response -> Mono.error(new CompositionUpstreamException("estudiantes", response.statusCode())))
                .bodyToMono(JsonNode.class)
                // Cualquier otro fallo (caído, timeout, 5xx) -> 503, nunca 500/502.
                .onErrorMap(e -> !(e instanceof StudentNotFoundException),
                        e -> new ModuloNoDisponibleException("Estudiantes", e));
    }

    // ---- Paso 2: GET Inscripciones?estudianteId={id} (todas las páginas) ----

    private Mono<List<InscripcionRaw>> fetchTodasLasInscripciones(Long estudianteId) {
        return fetchInscripcionesPage(estudianteId, 0).flatMap(primera -> {
            List<InscripcionRaw> datos = primera.data() == null ? List.of() : primera.data();
            if (primera.totalPages() <= 1) {
                return Mono.just(datos);
            }
            return Flux.range(1, primera.totalPages() - 1)
                    .flatMap(pagina -> fetchInscripcionesPage(estudianteId, pagina))
                    .map(p -> p.data() == null ? List.<InscripcionRaw>of() : p.data())
                    .reduce(new ArrayList<>(datos), (acumulado, siguiente) -> {
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

    // ---- Pasos 3-4: cursos y docentes; cada uno puede faltar sin tumbar la respuesta ----

    private Mono<List<InscripcionDetalle>> enriquecerConCursosYDocentes(List<InscripcionRaw> inscripciones,
                                                                       AtomicBoolean parcial) {
        List<Long> cursoIds = inscripciones.stream().map(InscripcionRaw::cursoId)
                .filter(Objects::nonNull).distinct().toList();

        return Flux.fromIterable(cursoIds)
                .flatMap(cursoId -> opcional(fetchCurso(cursoId), "curso " + cursoId, parcial))
                .filter(Optional::isPresent).map(Optional::get)
                .collectMap(CursoRaw::id)
                .flatMap(cursosPorId -> {
                    List<Long> docenteIds = cursosPorId.values().stream()
                            .map(CursoRaw::docenteId).filter(Objects::nonNull).distinct().toList();
                    return Flux.fromIterable(docenteIds)
                            .flatMap(docenteId -> opcional(fetchDocente(docenteId), "docente " + docenteId, parcial))
                            .filter(Optional::isPresent).map(Optional::get)
                            .collectMap(DocenteRaw::id)
                            .map(docentesPorId -> inscripciones.stream()
                                    .map(insc -> toDetalle(insc, cursosPorId.get(insc.cursoId()), docentesPorId, parcial))
                                    .toList());
                });
    }

    /** Convierte un fallo puntual (404, caído, timeout) en "dato no disponible" y marca la respuesta como parcial. */
    private static <T> Mono<Optional<T>> opcional(Mono<T> llamada, String que, AtomicBoolean parcial) {
        return llamada.map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("/detalle: {} no disponible ({})", que, e.toString());
                    parcial.set(true);
                    return Mono.just(Optional.empty());
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

    private static InscripcionDetalle toDetalle(InscripcionRaw insc, CursoRaw curso,
                                                Map<Long, DocenteRaw> docentesPorId, AtomicBoolean parcial) {
        if (curso == null) {
            parcial.set(true);
        }
        DocenteRaw docente = (curso == null || curso.docenteId() == null) ? null : docentesPorId.get(curso.docenteId());
        CursoDetalle cursoDetalle = curso == null ? null : new CursoDetalle(
                curso.id(), curso.materiaNombre(), curso.materiaSemestre(), curso.programaCodigo(),
                curso.horario(), curso.periodo(), curso.estado(),
                docente == null ? null : new DocenteDetalle(docente.id(), docente.nombreCompleto()));
        List<NotaDetalle> notas = insc.notas() == null ? List.of()
                : insc.notas().stream().map(n -> new NotaDetalle(n.tipo(), n.valor())).toList();
        return new InscripcionDetalle(insc.idInscripcion(), insc.periodo(), insc.estado(), cursoDetalle, notas);
    }
}
