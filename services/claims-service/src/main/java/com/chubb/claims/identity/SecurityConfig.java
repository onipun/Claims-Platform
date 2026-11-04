package com.chubb.claims.identity;

import com.chubb.claims.claim.domain.Role;
import com.chubb.claims.web.SecurityProblemHandlers;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless; verifies tokens with the local public key directly, with no HTTP call to itself. */
@Configuration
class SecurityConfig {

  @Bean
  JwtKeyStore jwtKeyStore(JwtProperties props) {
    if (props.keyDir() == null || props.keyDir().isBlank()) {
      throw new IllegalStateException("app.jwt.key-dir (APP_JWT_KEY_DIR) must be set");
    }
    return JwtKeyStore.loadOrCreate(Path.of(props.keyDir()));
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  JwtDecoder jwtDecoder(JwtKeyStore keys, JwtProperties props) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
        .signatureAlgorithm(SignatureAlgorithm.RS256).build();
    OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(props.issuer()));
    decoder.setJwtValidator(validator);
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      String role = jwt.getClaimAsString("role");
      for (Role r : Role.values()) {
        if (r.name().equals(role)) return List.of(new SimpleGrantedAuthority("ROLE_" + role));
      }
      return List.of();
    });
    return converter;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
      JwtAuthenticationConverter converter, SecurityProblemHandlers problems) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/v1/auth/login", "/api/v1/.well-known/jwks.json").permitAll()
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
            .requestMatchers("/api/v1/claimant/**").hasRole("CLAIMANT")
            .requestMatchers("/api/v1/staff/**").hasAnyRole("OFFICER", "MANAGER")
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o
            .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(problems)
            .accessDeniedHandler(problems))
        .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
        .build();
  }
}
