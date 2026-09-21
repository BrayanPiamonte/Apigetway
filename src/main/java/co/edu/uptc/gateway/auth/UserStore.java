package co.edu.uptc.gateway.auth;

import co.edu.uptc.gateway.config.GatewayProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Almacén de usuarios del Gateway en un archivo JSON (el Gateway no tiene base de datos propia).
 * Los nombres de usuario no distinguen mayúsculas. Las contraseñas solo se guardan como hash BCrypt.
 */
@Component
public class UserStore {

    private final Map<String, StoredUser> users = new ConcurrentHashMap<>();
    private final Path file;
    private final ObjectMapper mapper;
    private final PasswordEncoder encoder;
    /** Hash de relleno: se compara igual cuando el usuario no existe, para no revelarlo por tiempo de respuesta. */
    private final String dummyHash;

    public UserStore(GatewayProperties props, ObjectMapper mapper, PasswordEncoder encoder) {
        this.file = Path.of(props.usersFile()).toAbsolutePath();
        this.mapper = mapper;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("relleno-contra-timing");
        load();
        ensureAdmin(props.admin().username(), props.admin().password());
    }

    /** Usuario público si las credenciales son correctas; vacío si no. */
    public Optional<UserResponse> verify(String username, String rawPassword) {
        StoredUser user = users.get(key(username));
        boolean matches = encoder.matches(rawPassword, user != null ? user.passwordHash() : dummyHash);
        return user != null && matches ? Optional.of(toPublic(user)) : Optional.empty();
    }

    /** Crea un usuario con rol USER; vacío si el nombre ya existe. */
    public Optional<UserResponse> create(String username, String rawPassword) {
        StoredUser user = new StoredUser(username, encoder.encode(rawPassword), Role.USER, Instant.now().toString());
        synchronized (this) {
            if (users.putIfAbsent(key(username), user) != null) {
                return Optional.empty();
            }
            persist();
        }
        return Optional.of(toPublic(user));
    }

    private void ensureAdmin(String username, String rawPassword) {
        synchronized (this) {
            if (users.containsKey(key(username))) {
                return;
            }
            users.put(key(username),
                    new StoredUser(username, encoder.encode(rawPassword), Role.ADMIN, Instant.now().toString()));
            persist();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<StoredUser> stored = mapper.readValue(file.toFile(), new TypeReference<List<StoredUser>>() {
            });
            stored.forEach(u -> users.put(key(u.username()), u));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(users.values()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar " + file, e);
        }
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static UserResponse toPublic(StoredUser user) {
        return new UserResponse(user.username(), user.role().name());
    }
}
