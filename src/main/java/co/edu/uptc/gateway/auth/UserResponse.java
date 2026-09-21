package co.edu.uptc.gateway.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserResponse(
        @Schema(example = "admin") String username,
        @Schema(example = "ADMIN", allowableValues = {"ADMIN", "USER"}) String role) {
}
