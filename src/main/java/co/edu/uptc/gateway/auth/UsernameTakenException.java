package co.edu.uptc.gateway.auth;

public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException() {
        super("Ese nombre de usuario ya existe");
    }
}
