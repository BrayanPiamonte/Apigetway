package co.edu.uptc.gateway.composition.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Forma cruda de un docente según el módulo Materias (entidad Docente). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocenteRaw(Long id, String nombre, String apellido, String correo, String especialidad) {

    public String nombreCompleto() {
        return apellido == null || apellido.isBlank() ? nombre : nombre + " " + apellido;
    }
}
