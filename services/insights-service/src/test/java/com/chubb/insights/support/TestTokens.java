package com.chubb.insights.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** A test signing key, published (public half only) by a stub JWKS endpoint, like claims-service does. */
public final class TestTokens {
  private static final RSAKey KEY = generate();
  private static final HttpServer JWKS = start();

  private TestTokens() {}

  public static String jwksUri() {
    return "http://localhost:" + JWKS.getAddress().getPort() + "/jwks.json";
  }

  public static String token(String role, String... markets) {
    try {
      JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("claims-service").subject(UUID.randomUUID().toString())
          .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(Instant.now().plusSeconds(600)))
          .claim("role", role).claim("markets", List.of(markets)).claim("name", "Test").build();
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(KEY));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-kid").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer start() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      s.createContext("/jwks.json", ex -> {
        byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
