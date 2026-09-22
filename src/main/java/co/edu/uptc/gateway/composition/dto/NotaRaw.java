package co.edu.uptc.gateway.composition.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotaRaw(Long idNota, String tipo, Double valor, Double porcentaje) {
}
