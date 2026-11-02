package co.edu.uptc.gateway.security;

public enum Role {
    /** Todos los métodos sobre /api/**. */
    ADMIN,
    /** Solo lectura (GET) sobre /api/**. */
    USER
}
