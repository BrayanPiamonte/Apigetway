package co.edu.uptc.gateway.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(example = "admin") @NotBlank @Size(max = 64) String username,
        @Schema(example = "tu-contraseña", format = "password") @NotBlank @Size(max = 72) String password) {
}
