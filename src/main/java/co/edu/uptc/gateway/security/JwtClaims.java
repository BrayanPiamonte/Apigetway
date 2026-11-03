package co.edu.uptc.gateway.security;

/** Nombres de claims del JWT que emite el módulo de Autenticación. */
public final class JwtClaims {

    public static final String ROLE_CLAIM = "role";
    /** Id del estudiante vinculado al usuario (lo agrega el módulo de Autenticación al token). */
    public static final String ESTUDIANTE_ID_CLAIM = "estudianteId";

    private JwtClaims() {
    }
}
