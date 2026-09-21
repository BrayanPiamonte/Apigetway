package co.edu.uptc.gateway;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.uptc.gateway.auth.TokenResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pruebas de seguridad del Gateway. Los tres módulos apuntan a un puerto cerrado a propósito:
 * si la petición SUPERA la seguridad, el resultado esperado es 503 (módulo caído, error controlado);
 * si NO la supera, es 401 (sin/mal token) o 403 (rol sin permiso).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.jwt.secret=0123456789-0123456789-0123456789-0123",
                "gateway.admin.username=admin",
                "gateway.admin.password=admin-password-1",
                "gateway.users-file=target/test-users.json",
                "gateway.rate-limit-per-minute=1000",
                "gateway.services.estudiantes=http://localhost:1",
                "gateway.services.materias=http://localhost:1",
                "gateway.services.inscripciones=http://localhost:1"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewaySecurityTest {

    private static final String USER_PASSWORD = "clave-segura-1";

    @Autowired
    private WebTestClient client;

    private String adminToken;
    private String userToken;
    private String username;

    @BeforeAll
    void createTokens() {
        adminToken = login("admin", "admin-password-1");
        username = "user" + System.nanoTime();
        client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", USER_PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.role").isEqualTo("USER");
        userToken = login(username, USER_PASSWORD);
    }

    private String login(String user, String password) {
        TokenResponse body = client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", user, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult().getResponseBody();
        assertNotNull(body);
        return body.accessToken();
    }

    @Test
    void apiWithoutTokenIs401() {
        client.get().uri("/api/materias").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody().jsonPath("$.error.code").isEqualTo("TOKEN_REQUIRED");
    }

    @Test
    void garbageTokenIs401() {
        client.get().uri("/api/materias")
                .header(HttpHeaders.AUTHORIZATION, "Bearer basura")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    void wrongPasswordIs401() {
        client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "incorrecta"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void staleTokenDoesNotBreakLogin() {
        client.post().uri("/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-vencido-o-basura")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin-password-1"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void userCanReadButNotWrite() {
        // GET pasa la seguridad -> llega al módulo (caído) -> 503 controlado
        client.get().uri("/api/materias?pageNumber=1&pageSize=5")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE");

        // POST / DELETE los frena el Gateway con 403 antes de llegar al módulo
        client.post().uri("/api/materias/cursos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("periodo", "2026-2"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error.code").isEqualTo("FORBIDDEN");

        client.delete().uri("/api/estudiantes/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminCanWrite() {
        client.post().uri("/api/materias")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("nombre", "Sistemas Distribuidos"))
                .exchange()
                .expectStatus().isEqualTo(503); // pasó la seguridad; el módulo está caído
    }

    @Test
    void unknownApiRouteIs404ForAuthenticatedUser() {
        client.get().uri("/api/otra-cosa")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void meReturnsIdentityFromToken() {
        client.get().uri("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(username)
                .jsonPath("$.role").isEqualTo("USER");
    }

    @Test
    void registerRejectsDuplicatesAndIgnoresRole() {
        client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username.toUpperCase(), "password", USER_PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(409);

        client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "intruso" + System.nanoTime(), "password", USER_PASSWORD, "role", "ADMIN"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.role").isEqualTo("USER");
    }

    @Test
    void registerValidatesBody() {
        client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "a", "password", "123"))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void healthAndGatewayOpenApiArePublic() {
        client.get().uri("/health").exchange().expectStatus().isOk();
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.paths['/auth/login']").exists();
    }
}
