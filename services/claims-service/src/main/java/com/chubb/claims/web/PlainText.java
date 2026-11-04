package com.chubb.claims.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Free text may hold anything except control characters other than tab, line feed and carriage
 * return. NUL in particular cannot be stored in a Postgres text column and used to end in a 500
 * whose driver message carried the value into the log. Violations are 422 {@code VALIDATION_FAILED}.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*", message = "must not contain control characters")
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainText {
  String message() default "must not contain control characters";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
