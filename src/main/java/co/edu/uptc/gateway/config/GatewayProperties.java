package co.edu.uptc.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración propia del Gateway (prefijo "gateway"). Si falta un secreto obligatorio,
 * la aplicación no arranca y el mensaje indica la propiedad exacta.
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @Valid @DefaultValue Jwt jwt,
        @Valid @DefaultValue Admin admin,
        @Valid @DefaultValue Services services,
        @DefaultValue("./data/users.json") String usersFile,
        @DefaultValue("*") List<String> corsOrigins,
        @Min(1) @DefaultValue("10") int rateLimitPerMinute,
        // Timeout de las llamadas que el propio Gateway hace a los módulos para /detalle (distinto
        // del timeout del proxy simple, que se configura en spring.cloud.gateway.httpclient).
        @DefaultValue("5s") Duration compositionTimeout) {

    public record Jwt(
            @NotBlank(message = "es obligatorio (variable JWT_SECRET)")
            @Size(min = 32, message = "debe tener al menos 32 caracteres")
            String secret,
            @DefaultValue("1h") Duration expiresIn,
            @DefaultValue("api-gateway-uptc") String issuer,
            @DefaultValue("sistema-academico") String audience) {
    }

    public record Admin(
            @DefaultValue("admin") @Size(min = 3) String username,
            @NotBlank(message = "es obligatorio (variable ADMIN_PASSWORD)")
            @Size(min = 8, message = "debe tener al menos 8 caracteres")
            String password) {
    }

    public record Services(
            @DefaultValue("http://localhost:3001") String estudiantes,
            @DefaultValue("http://localhost:8081") String materias,
            @DefaultValue("http://localhost:8082") String inscripciones) {
    }
}
