package com.chubb.claims.identity;

import com.chubb.claims.reference.AppUser;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final JwtEncoder encoder;
  private final JwtProperties props;
  private final String keyId;

  public TokenService(JwtKeyStore keys, JwtProperties props) {
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    this.props = props;
    this.keyId = keys.keyId();
  }

  public record IssuedToken(String value, long expiresInSeconds) {}

  public IssuedToken issue(AppUser user) {
    // Wall-clock time on purpose: token lifetime is not business time and must not follow a test clock.
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(props.issuer()).subject(user.getId().toString())
        .issuedAt(now).expiresAt(now.plus(props.ttl()))
        .claim("role", user.getRole().name())
        .claim("markets", List.copyOf(user.getMarkets()))
        .claim("name", user.getName())
        .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedToken(token, props.ttl().toSeconds());
  }
}
