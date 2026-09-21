package co.edu.uptc.gateway.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(example = "laura.gomez", description = "3-32 caracteres: letras, números, _ . -")
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,32}$", message = "3-32 caracteres: letras, números, _ . -")
        String username,
        @Schema(example = "una-clave-segura", format = "password")
        @NotBlank
        @Size(min = 8, max = 72, message = "debe tener entre 8 y 72 caracteres")
        String password) {
}
