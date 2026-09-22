package co.edu.uptc.gateway.composition;

import org.springframework.http.HttpStatusCode;

/**
 * Un módulo respondió un error (no una caída ni un timeout, que ya maneja GatewayErrorHandler)
 * mientras el Gateway armaba /detalle. Se identifica el módulo y el status para facilitar el diagnóstico.
 */
public class CompositionUpstreamException extends RuntimeException {

    private final String module;
    private final int upstreamStatus;

    public CompositionUpstreamException(String module, HttpStatusCode upstreamStatus) {
        super("El módulo " + module + " respondió " + upstreamStatus.value() + " al construir /detalle");
        this.module = module;
        this.upstreamStatus = upstreamStatus.value();
    }

    public String module() {
        return module;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
