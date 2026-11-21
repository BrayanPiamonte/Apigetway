package co.edu.uptc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Simula los tres módulos con {@link HttpServer} (viene en el JDK, sin dependencias extra) para
 * comprobar la lógica real de /api/estudiantes/{id}/detalle: mapeo de campos, recorrido de todas
 * las páginas de Inscripciones, y que Curso/Docente no se pidan dos veces si se repiten.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.jwt.secret=" + TestTokens.SECRET,
                "gateway.datalake.enabled=false",
                "gateway.rate-limit-per-minute=1000",
                "gateway.composition-timeout=2s"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompositionTest {

    // Arrancan en un bloque estático (no en @BeforeAll): Spring evalúa @DynamicPropertySource
    // al construir el ApplicationContext, y eso ocurre ANTES de que corra @BeforeAll. Si los
    // servidores se levantaran ahí, los proveedores de abajo verían los campos todavía en null.
    private static final HttpServer estudiantes;
    private static final HttpServer materias;
    private static final HttpServer inscripciones;
    private static final AtomicInteger cursoCalls = new AtomicInteger();
    private static final AtomicInteger docenteCalls = new AtomicInteger();

    static {
        try {
            estudiantes = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            estudiantes.createContext("/api/estudiantes/12", exchange ->
                    respond(exchange, 200, "{\"id\":12,\"nombre\":\"Laura Gómez\",\"programa\":\"Ingeniería de Sistemas\"}"));
            estudiantes.createContext("/api/estudiantes/13", exchange ->
                    respond(exchange, 200, "{\"id\":13,\"nombre\":\"Carlos Ruiz\",\"programa\":\"Ingeniería de Sistemas\"}"));
            estudiantes.createContext("/api/estudiantes/14", exchange ->
                    respond(exchange, 200, "{\"id\":14,\"nombre\":\"Ana Torres\",\"programa\":\"Ingeniería de Sistemas\"}"));
            estudiantes.createContext("/api/estudiantes/15", exchange ->
                    respond(exchange, 500, "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}}"));
            estudiantes.createContext("/api/estudiantes/16", exchange ->
                    respond(exchange, 200, "{\"id\":16,\"nombre\":\"Luis Pérez\",\"programa\":\"Ingeniería de Sistemas\"}"));
            estudiantes.createContext("/api/estudiantes/99", exchange ->
                    respond(exchange, 404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no existe\"}}"));
            estudiantes.start();

            // Estudiante 12 -> 2 páginas (pageSize=50 siempre), ambas con el mismo cursoId=7 (prueba de deduplicación).
            // Estudiante 13 -> 1 inscripción con cursoId=8, cuyo curso responde 500 (respuesta parcial).
            // Estudiante 14 -> Inscripciones responde 500 (inscripciones = "NO_DISPONIBLE").
            // Estudiante 16 -> 1 inscripción con cursoId=9, que ya no existe en Materias (404, respuesta parcial).
            inscripciones = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            inscripciones.createContext("/api/inscripciones", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                if (query.contains("estudianteId=12") && query.contains("pageNumber=0")) {
                    respond(exchange, 200, """
                            {"data":[{"idInscripcion":45,"estudianteId":12,"cursoId":7,"periodo":"2026-2",
                            "estado":"ACTIVA","notas":[{"idNota":1,"tipo":"Parcial 1","valor":4.2,"porcentaje":30}]}],
                            "pageNumber":0,"pageSize":50,"totalElements":2,"totalPages":2}""");
                } else if (query.contains("estudianteId=12") && query.contains("pageNumber=1")) {
                    respond(exchange, 200, """
                            {"data":[{"idInscripcion":46,"estudianteId":12,"cursoId":7,"periodo":"2026-2",
                            "estado":"FINALIZADA","notas":[]}],
                            "pageNumber":1,"pageSize":50,"totalElements":2,"totalPages":2}""");
                } else if (query.contains("estudianteId=14")) {
                    respond(exchange, 500, "{\"status\":500,\"mensaje\":\"boom\"}");
                } else if (query.contains("estudianteId=16")) {
                    respond(exchange, 200, """
                            {"data":[{"idInscripcion":60,"estudianteId":16,"cursoId":9,"periodo":"2026-2",
                            "estado":"ACTIVA","notas":[]}],
                            "pageNumber":0,"pageSize":50,"totalElements":1,"totalPages":1}""");
                } else if (query.contains("estudianteId=13")) {
                    respond(exchange, 200, """
                            {"data":[{"idInscripcion":50,"estudianteId":13,"cursoId":8,"periodo":"2026-2",
                            "estado":"ACTIVA","notas":[]}],
                            "pageNumber":0,"pageSize":50,"totalElements":1,"totalPages":1}""");
                } else {
                    respond(exchange, 200, """
                            {"data":[],"pageNumber":0,"pageSize":50,"totalElements":0,"totalPages":0}""");
                }
            });
            inscripciones.start();

            materias = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            materias.createContext("/api/materias/cursos/7", exchange -> {
                cursoCalls.incrementAndGet();
                respond(exchange, 200, """
                        {"id":7,"materiaId":1,"materiaNombre":"Sistemas Distribuidos","docenteId":3,
                        "docenteNombre":"Andrés Vargas","horario":"Lunes-Miércoles 8:00-10:00","periodo":"2026-2","cupo":30,
                        "cuposDisponibles":12,"estado":"ACTIVO"}""");
            });
            materias.createContext("/api/materias/cursos/8", exchange -> respond(exchange, 500,
                    "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}}"));
            materias.createContext("/api/materias/cursos/9", exchange -> respond(exchange, 404,
                    "{\"status\":404,\"error\":\"Not Found\",\"message\":\"Curso con id 9 no fue encontrado(a)\"}"));
            materias.createContext("/api/materias/docentes/3", exchange -> {
                docenteCalls.incrementAndGet();
                respond(exchange, 200,
                        "{\"id\":3,\"nombre\":\"Andrés\",\"apellido\":\"Vargas\",\"correo\":\"a@uptc.edu.co\",\"especialidad\":\"Redes\"}");
            });
            materias.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private WebTestClient client;

    private String token;

    @DynamicPropertySource
    static void moduleUrls(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.estudiantes", () -> "http://127.0.0.1:" + estudiantes.getAddress().getPort());
        registry.add("gateway.services.materias", () -> "http://127.0.0.1:" + materias.getAddress().getPort());
        registry.add("gateway.services.inscripciones", () -> "http://127.0.0.1:" + inscripciones.getAddress().getPort());
    }

    @AfterAll
    static void stopMockModules() {
        estudiantes.stop(0);
        materias.stop(0);
        inscripciones.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String token() {
        if (token == null) {
            token = TestTokens.mint("admin", "ADMIN");
        }
        return token;
    }

    @Test
    void detalleSinTokenEs401() {
        client.get().uri("/api/estudiantes/12/detalle").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void ensamblaElDetalleCompletoYNoRepiteLlamadas() {
        cursoCalls.set(0);
        docenteCalls.set(0);

        client.get().uri("/api/estudiantes/12/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estudiante.nombre").isEqualTo("Laura Gómez")
                .jsonPath("$.estudiante.programa").isEqualTo("Ingeniería de Sistemas")
                .jsonPath("$.inscripciones.length()").isEqualTo(2) // las 2 páginas se recorrieron y se unieron
                .jsonPath("$.inscripciones[0].id").isEqualTo(45)
                .jsonPath("$.inscripciones[0].estado").isEqualTo("ACTIVA")
                .jsonPath("$.inscripciones[0].curso.id").isEqualTo(7)
                .jsonPath("$.inscripciones[0].curso.nombre").isEqualTo("Sistemas Distribuidos")
                .jsonPath("$.inscripciones[0].curso.docente.id").isEqualTo(3)
                .jsonPath("$.inscripciones[0].curso.docente.nombre").isEqualTo("Andrés Vargas")
                .jsonPath("$.inscripciones[0].notas[0].tipo").isEqualTo("Parcial 1")
                .jsonPath("$.inscripciones[0].notas[0].valor").isEqualTo(4.2)
                .jsonPath("$.inscripciones[1].id").isEqualTo(46)
                .jsonPath("$.inscripciones[0].curso.estado").isEqualTo("ACTIVO")
                .jsonPath("$.inscripciones[1].curso.id").isEqualTo(7) // mismo curso que la inscripción 45
                .jsonPath("$.parcial").isEqualTo(false);

        // Las dos inscripciones comparten cursoId=7 y docenteId=3: cada uno se pide una sola vez.
        assertEquals(1, cursoCalls.get());
        assertEquals(1, docenteCalls.get());
    }

    @Test
    void estudianteInexistenteEs404() {
        client.get().uri("/api/estudiantes/99/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error.code").isEqualTo("STUDENT_NOT_FOUND");
    }

    @Test
    void cursoQueFallaDaRespuestaParcialYNo500() {
        client.get().uri("/api/estudiantes/13/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.parcial").isEqualTo(true)
                .jsonPath("$.estudiante.nombre").isEqualTo("Carlos Ruiz")
                .jsonPath("$.inscripciones[0].id").isEqualTo(50)
                .jsonPath("$.inscripciones[0].curso").isEmpty();

        // El Gateway sigue sirviendo con normalidad tras el fallo.
        client.get().uri("/health").exchange().expectStatus().isOk();
    }

    @Test
    void cursoQueYaNoExisteDaRespuestaParcial() {
        client.get().uri("/api/estudiantes/16/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.parcial").isEqualTo(true)
                .jsonPath("$.inscripciones[0].id").isEqualTo(60)
                .jsonPath("$.inscripciones[0].curso").isEmpty();
    }

    @Test
    void inscripcionesCaidoDaNoDisponibleYParcial() {
        client.get().uri("/api/estudiantes/14/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.parcial").isEqualTo(true)
                .jsonPath("$.estudiante.nombre").isEqualTo("Ana Torres")
                .jsonPath("$.inscripciones").isEqualTo("NO_DISPONIBLE");
    }

    @Test
    void estudiantesConErrorEs503YNunca500() {
        client.get().uri("/api/estudiantes/15/detalle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error.code").isEqualTo("MODULE_UNAVAILABLE");
    }
}
