package co.edu.uptc.gateway.datalake;

import java.time.LocalDateTime;

/**
 * Una fila del datalake de peticiones. El esquema es el MISMO que escribe el simulador
 * (datalake/simulador/simular.py); no cambiar nombres ni tipos sin cambiar ambos.
 */
public record RegistroPeticion(
        String requestId,
        LocalDateTime ts,          // hora local de Bogotá
        String metodo,
        String path,
        String ruta,               // estudiantes | materias | inscripciones | auth | detalle | otro
        int status,
        int duracionMs,
        String usuario,
        String rol,
        Long estudianteId,
        Long cursoId,
        String ipCliente,
        String cache,              // HIT | MISS | null
        String idempotencyKey,
        String userAgent,
        String origen) {           // "gateway" (tiempo real) o "simulacion"
}
