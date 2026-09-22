package co.edu.uptc.gateway.composition;

/** El módulo Estudiantes respondió 404 para el id pedido. */
public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(Long id) {
        super("No existe un estudiante con id " + id);
    }
}
