package co.edu.uptc.gateway.composition;

/** Un módulo obligatorio para /detalle (Estudiantes) no respondió: se contesta 503, nunca 500. */
public class ModuloNoDisponibleException extends RuntimeException {

    private final String module;

    public ModuloNoDisponibleException(String module, Throwable cause) {
        super("El módulo " + module + " no está disponible, intenta más tarde", cause);
        this.module = module;
    }

    public String module() {
        return module;
    }
}
