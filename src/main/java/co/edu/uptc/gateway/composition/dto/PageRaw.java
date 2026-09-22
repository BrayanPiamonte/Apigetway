package co.edu.uptc.gateway.composition.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Página tal como la devuelve cada módulo (data / pageNumber / pageSize / totalElements / totalPages). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageRaw<T>(List<T> data, int pageNumber, int pageSize, long totalElements, int totalPages) {
}
