package co.edu.uptc.gateway.auth;

public enum Role {
    /** Todos los métodos sobre /api/**. */
    ADMIN,
    /** Solo lectura (GET) sobre /api/**. */
    USER
}
