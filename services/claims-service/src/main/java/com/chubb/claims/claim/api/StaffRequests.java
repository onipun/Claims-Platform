package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.RejectReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Request bodies of the staff commands. Amounts are accepted as JSON strings or numbers. */
public final class StaffRequests {
  private StaffRequests() {}

  public record Assign(@NotNull UUID officerId) {}

  public record Note(@com.chubb.claims.web.PlainText @NotBlank @Size(max = 4000) String text) {}

  public record InfoRequestBody(@com.chubb.claims.web.PlainText @NotBlank @Size(max = 4000) String question) {}

  public record Assessment(@NotNull BigDecimal reserve) {}

  public record Approval(@NotNull BigDecimal amount) {}

  @io.swagger.v3.oas.annotations.media.Schema(name = "RejectionRequest")
  public record Rejection(@NotNull RejectReason reason, @com.chubb.claims.web.PlainText @NotBlank @Size(max = 4000) String explanation) {}

  public record Settlement(@NotNull BigDecimal amount) {}
}
