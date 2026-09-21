package co.edu.uptc.gateway.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Formato de error único del Gateway: { "error": { "code", "message", "details" } }. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, List<Map<String, String>> details) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, null));
    }

    public static ApiError of(String code, String message, List<Map<String, String>> details) {
        return new ApiError(new Body(code, message, details));
    }
}
