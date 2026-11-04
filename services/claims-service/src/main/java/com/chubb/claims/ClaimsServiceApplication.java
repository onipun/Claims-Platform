package com.chubb.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class ClaimsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClaimsServiceApplication.class, args);
  }
}
