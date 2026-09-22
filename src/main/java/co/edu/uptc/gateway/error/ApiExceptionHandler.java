package co.edu.uptc.gateway.error;

import co.edu.uptc.gateway.auth.InvalidCredentialsException;
import co.edu.uptc.gateway.auth.UsernameTakenException;
import co.edu.uptc.gateway.composition.CompositionUpstreamException;
import co.edu.uptc.gateway.composition.StudentNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/** Errores de los endpoints propios del Gateway (/auth/**). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiError> validation(WebExchangeBindException ex) {
        List<Map<String, String>> details = ex.getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(),
                        "message", e.getDefaultMessage() != null ? e.getDefaultMessage() : "inválido"))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("VALIDATION_ERROR", "El cuerpo no cumple las reglas de validación", details));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiError> badInput(ServerWebInputException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_JSON", "JSON mal formado o faltante"));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("INVALID_CREDENTIALS", "Usuario o contraseña incorrectos"));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ApiError> usernameTaken(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("USERNAME_TAKEN", "Ese nombre de usuario ya existe"));
    }

    @ExceptionHandler(StudentNotFoundException.class)
    public ResponseEntity<ApiError> studentNotFound(StudentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("STUDENT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(CompositionUpstreamException.class)
    public ResponseEntity<ApiError> compositionUpstream(CompositionUpstreamException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("COMPOSITION_UPSTREAM_ERROR", ex.getMessage()));
    }
}
