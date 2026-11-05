package co.edu.uptc.gateway.inscripciones;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Inscripción "self-service" para estudiantes.
 * <p>
 * El endpoint normal POST /api/inscripciones exige rol ADMIN porque el cuerpo incluye el
 * estudianteId y nadie debería poder inscribir a otro. Aquí el estudianteId no se acepta del
 * cliente: se toma del claim {@code estudianteId} del JWT ya validado por el Gateway y se arma
 * la petición hacia el módulo Inscripciones. Así un USER puede inscribirse a sí mismo sin poder
 * suplantar a otro estudiante.
 */
@RestController
@RequestMapping("/api/inscripciones")
@Tag(name = "Inscripción del estudiante", description = "Inscribe al estudiante autenticado en un curso")
public class InscripcionPropiaController {

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String inscripcionesBase;

    public InscripcionPropiaController(WebClient compositionWebClient, ObjectMapper mapper, GatewayProperties props) {
        this.client = compositionWebClient;
        this.mapper = mapper;
        this.inscripcionesBase = props.services().inscripciones();
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
                    módulo Inscripciones. Devuelve el mismo cuerpo y código que responda ese módulo
                    (201 si se creó, 4xx si el curso no existe o el cupo está agotado).""")
    @ApiResponse(responseCode = "201", description = "Inscripción creada")
    @ApiResponse(responseCode = "403", description = "El usuario no está vinculado a un estudiante",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Cuerpo inválido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public Mono<ResponseEntity<JsonNode>> inscribir(
            @Valid @RequestBody SolicitudInscripcion solicitud,
            @AuthenticationPrincipal Jwt jwt) {

        Object claim = jwt.getClaim(JwtClaims.ESTUDIANTE_ID_CLAIM);
        if (claim == null || claim.toString().isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.valueToTree(ApiError.of("FORBIDDEN",
                            "Tu usuario no está vinculado a un estudiante: no puedes inscribir materias"))));
        }

        InscripcionRequest peticion = new InscripcionRequest(
                Long.valueOf(claim.toString()), solicitud.cursoId(), solicitud.periodo());

        return client.post()
                .uri(inscripcionesBase + "/api/inscripciones")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(peticion)
                .exchangeToMono(respuesta -> respuesta.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(cuerpo -> ResponseEntity.status(respuesta.statusCode())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(leer(cuerpo))));
    }

    /** Cuerpo que espera el módulo Inscripciones. */
    private record InscripcionRequest(Long estudianteId, Long cursoId, String periodo) {
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
