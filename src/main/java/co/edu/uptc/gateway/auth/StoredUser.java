package co.edu.uptc.gateway.auth;

/** Registro persistido en data/users.json (la contraseña solo se guarda como hash BCrypt). */
public record StoredUser(String username, String passwordHash, Role role, String createdAt) {
}
