package com.chubb.insights.config;

import com.chubb.insights.web.SecurityProblemHandlers;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Verifies tokens against the claims-service JWKS and holds no private key, so it cannot mint
 * (D11). The key set is fetched lazily on the first token, so insights starts without claims-service.
 */
@Configuration
class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
      @Value("${app.jwt.issuer:claims-service}") String issuer) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
        .jwsAlgorithm(SignatureAlgorithm.RS256).build(); // builds without fetching anything
    OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
    decoder.setJwtValidator(validator);
    return decoder;
  }

  /** The {@code role} claim becomes ROLE_OFFICER / ROLE_MANAGER / ROLE_CLAIMANT; anything else gets no role. */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      String role = jwt.getClaimAsString("role");
      return List.of("CLAIMANT", "OFFICER", "MANAGER").contains(role)
          ? List.of(new SimpleGrantedAuthority("ROLE_" + role)) : List.of();
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
            .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/v1/insights/**").hasAnyRole("OFFICER", "MANAGER")
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o
            .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(problems)
            .accessDeniedHandler(problems))
        .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
        .build();
  }
}
