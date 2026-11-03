package co.edu.uptc.gateway;

import java.time.Duration;
import java.time.Instant;
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
 * Pruebas de seguridad del Gateway. Los módulos (incluido Autenticación) apuntan a un puerto cerrado a propósito:
 * si la petición SUPERA la seguridad, el resultado esperado es 503 (módulo caído, error controlado);
 * si NO la supera, es 401 (sin/mal token) o 403 (rol sin permiso).
 * Los tokens se fabrican en el test con la misma clave: el Gateway solo los valida, no los emite.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.jwt.secret=" + TestTokens.SECRET,
                "gateway.rate-limit-per-minute=1000",
                "gateway.services.estudiantes=http://localhost:1",
                "gateway.services.materias=http://localhost:1",
                "gateway.services.inscripciones=http://localhost:1",
                "gateway.services.auth=http://localhost:1"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewaySecurityTest {

    @Autowired
    private WebTestClient client;

    private String adminToken;
    private String userToken;

    @BeforeAll
    void createTokens() {
        adminToken = TestTokens.mint("admin", "ADMIN");
        userToken = TestTokens.mint("laura", "USER");
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
    void tokenSignedWithAnotherKeyIs401() {
        Instant now = Instant.now();
        String forged = TestTokens.mint("admin", "ADMIN", "otra-clave-0123456789-0123456789-0123456789",
                now, now.plus(Duration.ofHours(1)));
        client.get().uri("/api/materias")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    void expiredTokenIs401() {
        Instant now = Instant.now();
        String expired = TestTokens.mint("admin", "ADMIN", TestTokens.SECRET,
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
        client.get().uri("/api/materias")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void loginIsPublicAndRoutedToAuthModule() {
        // Pasa la seguridad (es público) y llega al módulo de Autenticación (caído) -> 503 controlado
        client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "x"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void staleTokenDoesNotBreakLogin() {
        client.post().uri("/api/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-vencido-o-basura")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "x"))
                .exchange()
                .expectStatus().isEqualTo(503); // no 401: en rutas públicas se ignora el Authorization
    }

    @Test
    void registerAndMeAreNotPublic() {
        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "nuevo", "password", "clave-segura-1"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void registerIsAdminOnly() {
        Map<String, String> body = Map.of("username", "nuevo", "password", "clave-segura-1");
        client.post().uri("/api/auth/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/auth/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isEqualTo(503); // pasó la seguridad; Autenticación está caído
    }

    @Test
    void meWithValidTokenReachesAuthModule() {
        client.get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange().expectStatus().isEqualTo(503);
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
    void studentCanOnlySeeOwnDetalle() {
        String student12 = TestTokens.mintStudent("laura", 12);

        // El suyo: pasa la seguridad (después puede fallar por otra causa, pero nunca 401/403)
        client.get().uri("/api/estudiantes/12/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + student12)
                .exchange()
                .expectStatus().value(s -> org.junit.jupiter.api.Assertions.assertTrue(s != 401 && s != 403,
                        "su propio /detalle no debe ser rechazado, pero fue " + s));

        // El de otro estudiante: 403
        client.get().uri("/api/estudiantes/13/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + student12)
                .exchange()
                .expectStatus().isForbidden();

        // Un USER sin claim estudianteId tampoco puede ver ningún /detalle
        client.get().uri("/api/estudiantes/12/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange()
                .expectStatus().isForbidden();

        // ADMIN puede ver cualquiera
        client.get().uri("/api/estudiantes/13/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().value(s -> org.junit.jupiter.api.Assertions.assertTrue(s != 401 && s != 403,
                        "ADMIN no debe ser rechazado, pero fue " + s));
    }

    @Test
    void studentCanOnlyUploadOwnPhoto() {
        String student12 = TestTokens.mintStudent("laura", 12);
        client.put().uri("/api/estudiantes/12/foto")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + student12)
                .exchange().expectStatus().isEqualTo(503); // pasó la seguridad; Estudiantes está caído
        client.put().uri("/api/estudiantes/13/foto")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + student12)
                .exchange().expectStatus().isForbidden();
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
    void inscripcionesRequiresTokenAndRespectsRoles() {
        // sin token -> 401, también en las rutas anidadas (notas e historial)
        client.get().uri("/api/inscripciones").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/inscripciones/1/notas").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/inscripciones/1/historial").exchange().expectStatus().isUnauthorized();

        // USER puede consultar: pasa la seguridad y llega al módulo (caído) -> 503 controlado
        client.get().uri("/api/inscripciones?pageNumber=0&pageSize=20&estado=ACTIVA")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange().expectStatus().isEqualTo(503);
        client.get().uri("/api/inscripciones/1/notas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange().expectStatus().isEqualTo(503);

        // USER no puede escribir: POST, PUT, PATCH (cambio de estado) ni DELETE -> 403
        client.post().uri("/api/inscripciones")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estudianteId", 1, "cursoId", 1))
                .exchange().expectStatus().isForbidden();
        client.patch().uri("/api/inscripciones/1/estado")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", "CANCELADA"))
                .exchange().expectStatus().isForbidden();
        client.delete().uri("/api/inscripciones/1/historial/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange().expectStatus().isForbidden();

        // ADMIN sí pasa la seguridad (503 porque el módulo está caído)
        client.patch().uri("/api/inscripciones/1/estado")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", "CANCELADA"))
                .exchange().expectStatus().isEqualTo(503);
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
    void oldGatewayAuthPathsAreGone() {
        // /auth/login ya no existe en el Gateway: la ruta pública es /api/auth/login
        client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "x"))
                .exchange().expectStatus().is4xxClientError();
    }

    @Test
    void healthAndGatewayOpenApiArePublic() {
        client.get().uri("/health").exchange().expectStatus().isOk();
        client.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
    }
}
