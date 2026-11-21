package co.edu.uptc.gateway.inscripciones;

/**
 * El estudiante intenta inscribirse en un curso cuya materia pertenece a otro
 * programa académico. El Gateway lo rechaza con 422 antes de llamar al módulo
 * Inscripciones.
 */
public class ProgramaIncompatibleException extends RuntimeException {

    public ProgramaIncompatibleException(String message) {
        super(message);
    }
}
