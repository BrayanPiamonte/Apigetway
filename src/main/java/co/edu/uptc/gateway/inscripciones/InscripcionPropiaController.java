package co.edu.uptc.gateway.inscripciones;

import co.edu.uptc.gateway.cache.MateriasCacheFilter;
import co.edu.uptc.gateway.config.GatewayProperties;
import co.edu.uptc.gateway.error.ApiError;
import co.edu.uptc.gateway.security.JwtClaims;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Inscripción "self-service" para estudiantes y alta de inscripciones para ADMIN.
 * <p>
 * El endpoint normal POST /api/inscripciones exige rol ADMIN porque el cuerpo incluye el
 * estudianteId y nadie debería poder inscribir a otro. Aquí el estudianteId no se acepta del
 * cliente en /mias: se toma del claim {@code estudianteId} del JWT ya validado por el Gateway.
 * <p>
 * En ambos casos el Gateway valida, antes de llamar al módulo Inscripciones, que la materia del
 * curso pertenezca al programa del estudiante. Así no se puede terminar con materias de varias
 * carreras aunque se llame directamente a la API.
 */
@RestController
@RequestMapping("/api/inscripciones")
@Tag(name = "Inscripción del estudiante", description = "Inscribe a un estudiante en un curso")
public class InscripcionPropiaController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String inscripcionesBase;
    private final ProgramaCompatibilityService compatibility;
    private final ObjectProvider<MateriasCacheFilter> cache;

    public InscripcionPropiaController(WebClient compositionWebClient, ObjectMapper mapper,
            GatewayProperties props, ProgramaCompatibilityService compatibility,
            ObjectProvider<MateriasCacheFilter> cache) {
        this.client = compositionWebClient;
        this.mapper = mapper;
        this.inscripcionesBase = props.services().inscripciones();
        this.compatibility = compatibility;
        this.cache = cache;
    }

    /** El cliente solo elige curso y periodo; el estudiante sale del token. */
    public record SolicitudInscripcion(
            @NotNull @Positive Long cursoId,
            @NotBlank String periodo) {
    }

    @PostMapping("/mias")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Inscribir al estudiante autenticado en un curso",
            description = """
                    Toma el estudianteId del claim `estudianteId` del token y crea la inscripción en el
                    módulo Inscripciones. Antes valida que el curso pertenezca al programa del estudiante.
                    Devuelve el mismo cuerpo y código que responda ese módulo
                    (201 si se creó, 409 sin cupo o ya inscrito, 422 curso/estudiante inactivo,
                    503 si un módulo no responde).

                    Consistencia A2: el encabezado `Idempotency-Key` se reenvía al módulo
                    Inscripciones; una repetición con la misma clave devuelve la inscripción anterior
                    (`Idempotent-Replayed: true`) y no crea otra.""")
    @ApiResponse(responseCode = "201", description = "Inscripción creada")
    @ApiResponse(responseCode = "403", description = "El usuario no está vinculado a un estudiante",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "La ventana de inscripción está cerrada",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Cuerpo inválido o materia de otro programa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public Mono<ResponseEntity<JsonNode>> inscribir(
            @Valid @RequestBody SolicitudInscripcion solicitud,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        Object claim = jwt.getClaim(JwtClaims.ESTUDIANTE_ID_CLAIM);
        if (claim == null || claim.toString().isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.valueToTree(ApiError.of("FORBIDDEN",
                            "Tu usuario no está vinculado a un estudiante: no puedes inscribir materias"))));
        }

        InscripcionRequest peticion = new InscripcionRequest(
                Long.valueOf(claim.toString()), solicitud.cursoId(), solicitud.periodo(), null);

        return inscripcionHabilitada()
                .flatMap(habilitada -> Boolean.TRUE.equals(habilitada)
                        ? validarYEnviar(peticion, idempotencyKey)
                        : Mono.just(json(HttpStatus.CONFLICT,
                                ApiError.of("INSCRIPCION_CERRADA",
                                        "No está en fecha disponible para inscripción de materias"))))
                .onErrorResume(e -> e instanceof WebClientResponseException || e instanceof WebClientRequestException,
                        e -> Mono.just(json(HttpStatus.SERVICE_UNAVAILABLE,
                                ApiError.of("CONFIGURACION_NO_DISPONIBLE",
                                        "No se pudo verificar la fecha de inscripción de materias"))));
    }

    /** Consulta el interruptor global en el módulo Inscripciones; ante error se cierra la ventana. */
    private Mono<Boolean> inscripcionHabilitada() {
        return client.get()
                .uri(inscripcionesBase + "/api/inscripciones/configuracion")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(configuracion -> configuracion.path("habilitada").asBoolean(false));
    }

    private Mono<ResponseEntity<JsonNode>> validarYEnviar(InscripcionRequest peticion, String idempotencyKey) {
        return compatibility.validar(peticion.estudianteId(), peticion.cursoId())
                .then(Mono.defer(() -> enviar(peticion, idempotencyKey)))
                // La inscripción descontó un cupo en Materias: el catálogo cacheado queda viejo.
                .flatMap(r -> r.getStatusCode().is2xxSuccessful() ? invalidarCatalogo().thenReturn(r) : Mono.just(r))
                // Un módulo que no responde es 503 (nunca 500 ni 502), igual que en el módulo Inscripciones.
                .onErrorResume(WebClientRequestException.class, e -> Mono.just(
                        json(HttpStatus.SERVICE_UNAVAILABLE,
                                ApiError.of("MODULE_UNAVAILABLE",
                                        "Un módulo no está disponible, intenta en unos minutos"))))
                .onErrorResume(ProgramaIncompatibleException.class, e -> Mono.just(
                        json(HttpStatus.UNPROCESSABLE_ENTITY,
                                ApiError.of("PROGRAMA_INCOMPATIBLE", e.getMessage()))))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.just(
                        json(HttpStatus.NOT_FOUND,
                                ApiError.of("INSCRIPCION_NO_ENCONTRADA",
                                        "El estudiante o el curso indicado no existe"))))
                .onErrorResume(WebClientResponseException.class, e -> Mono.just(
                        json(HttpStatus.BAD_GATEWAY,
                                ApiError.of("UPSTREAM_ERROR",
                                        "No se pudo validar el programa del curso en los módulos"))));
    }

    private Mono<ResponseEntity<JsonNode>> enviar(InscripcionRequest peticion, String idempotencyKey) {
        return client.post()
                .uri(inscripcionesBase + "/api/inscripciones")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        h.set(IDEMPOTENCY_KEY, idempotencyKey);
                    }
                })
                .bodyValue(peticion)
                .exchangeToMono(respuesta -> respuesta.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(cuerpo -> {
                            ResponseEntity.BodyBuilder r = ResponseEntity.status(respuesta.statusCode())
                                    .contentType(MediaType.APPLICATION_JSON);
                            respuesta.headers().header(REPLAYED).stream().findFirst()
                                    .ifPresent(v -> r.header(REPLAYED, v));
                            return r.body(leer(cuerpo));
                        }));
    }

    private Mono<Void> invalidarCatalogo() {
        MateriasCacheFilter filtro = cache.getIfAvailable();
        return filtro == null ? Mono.empty() : filtro.invalidarCatalogo();
    }

    private ResponseEntity<JsonNode> json(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.valueToTree(error));
    }

    /** Cuerpo que espera el módulo Inscripciones. */
    private record InscripcionRequest(Long estudianteId, Long cursoId, String periodo, String estado) {
    }

    private JsonNode leer(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return mapper.readTree(cuerpo);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(cuerpo);
        }
    }
}
