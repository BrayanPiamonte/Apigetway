package co.edu.uptc.gateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Gateway", description = "Estado del propio Gateway")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Chequeo de disponibilidad del Gateway")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
