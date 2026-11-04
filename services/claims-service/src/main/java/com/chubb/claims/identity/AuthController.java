package com.chubb.claims.identity;

import com.chubb.claims.claim.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {
  private final AuthService auth;
  private final JwtKeyStore keys;

  AuthController(AuthService auth, JwtKeyStore keys) {
    this.auth = auth;
    this.keys = keys;
  }

  record LoginRequest(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 200) String password) {}

  record UserView(UUID id, String name, Role role, Set<String> markets) {}

  record LoginResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}

  @PostMapping("/api/v1/auth/login")
  LoginResponse login(@Valid @RequestBody LoginRequest request) {
    AuthService.Login l = auth.login(request.email(), request.password());
    return new LoginResponse(l.token().value(), "Bearer", l.token().expiresInSeconds(),
        new UserView(l.user().getId(), l.user().getName(), l.user().getRole(), l.user().getMarkets()));
  }

  @GetMapping(value = "/api/v1/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> jwks() {
    return keys.publicJwks();
  }
}
