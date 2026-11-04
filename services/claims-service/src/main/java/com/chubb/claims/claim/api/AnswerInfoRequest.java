package com.chubb.claims.claim.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnswerInfoRequest(@com.chubb.claims.web.PlainText @NotBlank @Size(max = 4000) String answer) {}
