package co.edu.uptc.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.function.Predicate;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Convierte cualquier fallo al reenviar (módulo caído, lento, ruta inexistente) en un error JSON controlado.
 * Se ejecuta antes que el handler por defecto de Spring Boot (DefaultErrorWebExceptionHandler, orden -1).
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        // 1) Timeouts (incluye ConnectTimeoutException, que es un tipo de ConnectException: va primero).
        if (anyCause(ex, t -> t.getClass().getSimpleName().endsWith("TimeoutException"))) {
            return JsonErrors.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                    "El módulo " + moduleName(exchange, ex) + " tardó demasiado en responder");
        }
        // 2) Módulo caído o inalcanzable.
        if (anyCause(ex, t -> t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof UnresolvedAddressException)) {
            return JsonErrors.write(exchange, HttpStatus.BAD_GATEWAY, "BAD_GATEWAY",
                    "El módulo " + moduleName(exchange, ex) + " no está disponible");
        }
        // 3) Errores HTTP conocidos (404 sin ruta, 405, 400...).
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode status = rse.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String code = known != null ? known.name() : "ERROR";
            String message = status.value() == 404
                    ? "Ruta no encontrada: " + exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value()
                    : (rse.getReason() != null ? rse.getReason() : code);
            return JsonErrors.write(exchange, status, code, message);
        }
        // 4) Cualquier otra cosa.
        log.error("Error no controlado en {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex);
        return JsonErrors.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Error interno del Gateway");
    }

    /** Deduce el módulo del path de la llamada que falló (la del WebClient en /detalle, o la petición proxy). */
    private static String moduleName(ServerWebExchange exchange, Throwable ex) {
        String path = exchange.getRequest().getPath().value();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof WebClientRequestException wcre) {
                path = wcre.getUri().getPath();
                break;
            }
        }
        if (path.startsWith("/api/materias")) {
            return "Materias";
        }
        if (path.startsWith("/api/inscripciones")) {
            return "Inscripciones";
        }
        if (path.startsWith("/api/auth")) {
            return "Autenticación";
        }
        if (path.startsWith("/api/estudiantes") || path.startsWith("/api/programas") || path.startsWith("/api/documentos")) {
            return "Estudiantes";
        }
        return "solicitado";
    }

    private static boolean anyCause(Throwable ex, Predicate<Throwable> test) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (test.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
