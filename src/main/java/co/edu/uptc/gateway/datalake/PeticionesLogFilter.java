package co.edu.uptc.gateway.datalake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Registra cada petición que atraviesa el Gateway en el datalake (una fila por petición).
 * Va antes que todos los filtros (incluida la seguridad), así que también quedan los 401, 403,
 * 429 y las respuestas de /detalle. Devuelve X-Request-Id para poder correlacionar.
 */
public class PeticionesLogFilter implements WebFilter, Ordered {

    static final String REQUEST_ID = "X-Request-Id";
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Pattern ESTUDIANTE = Pattern.compile("^/api/estudiantes/(\\d+)");
    private static final Pattern CURSO = Pattern.compile("^/api/materias/cursos/(\\d+)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ParquetDatalakeWriter writer;

    public PeticionesLogFilter(ParquetDatalakeWriter writer) {
        this.writer = writer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (HttpMethod.OPTIONS.equals(request.getMethod()) || excluido(path)) {
            return chain.filter(exchange);
        }
        String requestId = UUID.randomUUID().toString();
        long inicio = System.nanoTime();
        LocalDateTime ts = LocalDateTime.now(BOGOTA);
        exchange.getResponse().getHeaders().set(REQUEST_ID, requestId);
        // Se copian los encabezados AL ENTRAR: más adelante IdentityHeadersFilter retira el
        // Authorization de la petición (y esos cambios se ven sobre los mismos encabezados).
        Entrada entrada = new Entrada(
                String.valueOf(request.getMethod()), path,
                request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                request.getHeaders().getFirst("Idempotency-Key"),
                request.getHeaders().getFirst(HttpHeaders.USER_AGENT),
                ip(exchange));

        return chain.filter(exchange).doFinally(signal -> {
            try {
                writer.registrar(construir(exchange, entrada, requestId, ts, inicio));
            } catch (RuntimeException ignored) {
                // registrar nunca debe romper una petición
            }
        });
    }

    private record Entrada(String metodo, String path, String authorization, String idempotencyKey,
                           String userAgent, String ip) {
    }

    static boolean excluido(String path) {
        return path.equals("/health") || path.startsWith("/swagger") || path.startsWith("/webjars")
                || path.startsWith("/v3/api-docs") || path.startsWith("/docs/") || path.equals("/favicon.ico");
    }

    private RegistroPeticion construir(ServerWebExchange exchange, Entrada entrada, String requestId,
                                       LocalDateTime ts, long inicio) {
        String path = entrada.path();
        HttpStatusCode code = exchange.getResponse().getStatusCode();
        int status = code == null ? 200 : code.value();
        int duracionMs = (int) ((System.nanoTime() - inicio) / 1_000_000);

        // Usuario y rol: se leen del payload del JWT solo para el registro (la validación real la hizo
        // la seguridad). Si la petición terminó en 401, no se confía en el token.
        String usuario = null;
        String rol = null;
        Long estudianteToken = null;
        JsonNode claims = status == 401 ? null : claims(entrada.authorization());
        if (claims != null) {
            usuario = texto(claims, "sub");
            rol = texto(claims, "role");
            if (claims.hasNonNull("estudianteId")) estudianteToken = claims.get("estudianteId").asLong();
        }

        Long estudianteId = numero(ESTUDIANTE, path);
        if (estudianteId == null) estudianteId = estudianteToken;

        return new RegistroPeticion(
                requestId, ts, entrada.metodo(), path, ruta(path), status, duracionMs,
                usuario, rol, estudianteId, numero(CURSO, path), entrada.ip(),
                exchange.getResponse().getHeaders().getFirst("X-Gateway-Cache"),
                entrada.idempotencyKey(), entrada.userAgent(), "gateway");
    }

    static String ruta(String path) {
        if (path.endsWith("/detalle")) return "detalle";
        if (path.startsWith("/api/estudiantes") || path.startsWith("/api/programas") || path.startsWith("/api/documentos")) {
            return "estudiantes";
        }
        if (path.startsWith("/api/materias")) return "materias";
        if (path.startsWith("/api/inscripciones")) return "inscripciones";
        if (path.startsWith("/api/auth")) return "auth";
        return "otro";
    }

    private static String ip(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }

    private static JsonNode claims(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String[] partes = authorization.substring(7).split("\\.");
        if (partes.length < 2) return null;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(partes[1]);
            return JSON.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
    }

    private static Long numero(Pattern p, String path) {
        Matcher m = p.matcher(path);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }
}
