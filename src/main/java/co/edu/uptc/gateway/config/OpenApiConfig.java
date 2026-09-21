package co.edu.uptc.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String DESCRIPTION = """
            API Gateway del **Sistema Académico** (Laboratorio 3 - Sistemas Distribuidos, UPTC). \
            Único punto de entrada hacia los módulos y **único lugar donde se aplica la seguridad**.

            ### Enrutamiento por prefijo
            | Prefijo | Módulo |
            |---------|--------|
            | `/api/estudiantes/**`, `/api/programas/**`, `/api/documentos/**` | Estudiantes |
            | `/api/materias/**` (incluye `/docentes` y `/cursos`) | Materias |
            | `/api/inscripciones/**` | Inscripciones |

            Método, query params y body se reenvían sin alterarse. Si un módulo no responde, el Gateway \
            devuelve `503` (caído) o `504` (tardó demasiado) sin dejar de atender a los demás.

            ### Seguridad (JSON Web Token)
            1. `POST /auth/login` devuelve un `accessToken`.
            2. Pulsa **Authorize** y pega el token (solo el token, sin "Bearer").
            3. Todo `/api/**` exige `Authorization: Bearer <token>`.

            | Rol | Permisos en `/api/**` |
            |-----|----------------------|
            | `ADMIN` | Todos los métodos |
            | `USER` | Solo lectura (`GET`) |

            El Gateway retira la cabecera `Authorization` antes de reenviar y agrega `X-User-Name` y `X-User-Role`.

            ### Documentación de los módulos
            El selector **Select a definition** (arriba a la derecha) cambia entre este contrato y el de cada \
            módulo; sus rutas se ejecutan a través del Gateway, con el mismo token.

            ### Formato de errores
            `{ "error": { "code", "message", "details": [] } }`
            """;

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Gateway - Sistema Académico")
                        .version("v1")
                        .description(DESCRIPTION))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
