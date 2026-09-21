package co.edu.uptc.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.function.Predicate;
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
                    "El módulo tardó demasiado en responder");
        }
        // 2) Módulo caído o inalcanzable.
        if (anyCause(ex, t -> t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof UnresolvedAddressException)) {
            return JsonErrors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "El módulo no está disponible");
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
