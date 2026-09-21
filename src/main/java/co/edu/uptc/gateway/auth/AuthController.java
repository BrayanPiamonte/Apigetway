package co.edu.uptc.gateway.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/auth")
@Tag(name = "Autenticación", description = "Emisión y consulta de tokens JWT")
public class AuthController {

    private final UserStore users;
    private final TokenService tokens;

    public AuthController(UserStore users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión y obtener un token JWT")
    public Mono<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        // BCrypt es costoso en CPU: se ejecuta fuera del hilo del event loop de Netty.
        return Mono.fromCallable(() -> users.verify(request.username(), request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> user.isPresent()
                        ? Mono.just(tokens.issue(user.get()))
                        : Mono.<TokenResponse>error(new InvalidCredentialsException()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar un usuario (rol USER, solo lectura)")
    public Mono<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Mono.fromCallable(() -> users.create(request.username(), request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> user.isPresent()
                        ? Mono.just(user.get())
                        : Mono.<UserResponse>error(new UsernameTakenException()));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Usuario del token actual")
    public Mono<UserResponse> me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return Mono.just(new UserResponse(jwt.getSubject(), jwt.getClaimAsString(TokenService.ROLE_CLAIM)));
    }
}
