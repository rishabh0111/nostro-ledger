package io.nostro.api.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Where staff turn a password into a short-lived token (ADR-0006). */
@RestController
class LoginController {

    /** A hash to compare against when the username is unknown, so that lookups take the same time either way. */
    private final String unknownUserHash;

    private final Credentials credentials;
    private final PasswordEncoder passwords;
    private final StaffTokens tokens;

    LoginController(Credentials credentials, PasswordEncoder passwords, StaffTokens tokens) {
        this.credentials = credentials;
        this.passwords = passwords;
        this.tokens = tokens;
        this.unknownUserHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Public
    @PostMapping(SecurityConfiguration.LOGIN_PATH)
    ResponseEntity<Token> login(@RequestBody Login login) {
        var user = credentials.staffUser(login.username());
        boolean matches = passwords.matches(login.password(), user.map(Credentials.StaffUser::passwordHash).orElse(unknownUserHash));
        if (user.isEmpty() || !matches) {
            throw new BadCredentialsException("username or password is wrong");
        }
        var issued = tokens.issue(user.get().caller().id());
        return ResponseEntity.ok(new Token(issued.token(), issued.expiresAt()));
    }

    record Login(String username, String password) {
    }

    record Token(String token, Instant expiresAt) {
    }
}
