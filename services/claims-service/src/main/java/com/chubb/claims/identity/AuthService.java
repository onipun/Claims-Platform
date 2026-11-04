package com.chubb.claims.identity;

import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final AppUserRepository users;
  private final PasswordEncoder encoder;
  private final TokenService tokens;
  /** Checked when the user does not exist, so the response time does not reveal which case it was. */
  private final String dummyHash;

  public AuthService(AppUserRepository users, PasswordEncoder encoder, TokenService tokens) {
    this.users = users;
    this.encoder = encoder;
    this.tokens = tokens;
    this.dummyHash = encoder.encode("unused-" + UUID.randomUUID());
  }

  public record Login(AppUser user, TokenService.IssuedToken token) {}

  @Transactional(readOnly = true)
  public Login login(String email, String password) {
    AppUser user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
    boolean matches = encoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
    if (user == null || !matches) throw new InvalidCredentialsException();
    return new Login(user, tokens.issue(user));
  }
}
