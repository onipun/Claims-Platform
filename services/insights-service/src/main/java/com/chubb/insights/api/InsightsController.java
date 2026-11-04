package com.chubb.insights.api;

import com.chubb.insights.api.InsightsDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Staff-only team views over the read model (D5). Every query is scoped to the token's {@code markets}; the
 * optional {@code market} filter can only narrow that scope, never widen it.
 */
@RestController
@RequestMapping("/api/v1/insights")
class InsightsController {
  private static final Pattern MARKET = Pattern.compile("^[A-Z]{2}$");

  private final InsightsService service;

  InsightsController(InsightsService service) {
    this.service = service;
  }

  @GetMapping("/exposure")
  @Operation(summary = "Case exposure per market, line and currency (case reserves, excluding IBNR)")
  ExposureResponse exposure(@AuthenticationPrincipal Jwt jwt,
      @Parameter(description = "Narrow to these markets; markets the caller lacks are ignored")
      @RequestParam(name = "market", required = false) List<String> market) {
    return service.exposure(scope(jwt, market));
  }

  @GetMapping("/workload")
  @Operation(summary = "Open claims per officer, team totals, unassigned count and ageing buckets")
  WorkloadResponse workload(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "market", required = false) List<String> market) {
    return service.workload(scope(jwt, market));
  }

  @GetMapping("/performance")
  @Operation(summary = "Decision and cycle-time figures over the last N days, by market and per officer")
  PerformanceResponse performance(@AuthenticationPrincipal Jwt jwt,
      @Parameter(description = "Window in days, 1 to 365") @RequestParam(name = "days", defaultValue = "30") int days,
      @RequestParam(name = "market", required = false) List<String> market) {
    if (days < 1 || days > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return service.performance(scope(jwt, market), days);
  }

  /** Token markets, intersected with the filter when one is given. A market the caller lacks yields nothing. */
  private static List<String> scope(Jwt jwt, List<String> requested) {
    List<String> mine = jwt.getClaimAsStringList("markets");
    List<String> allowed = mine == null ? List.of() : mine.stream().distinct().sorted().toList();
    if (requested == null || requested.isEmpty()) return allowed;
    for (String r : requested) {
      if (r == null || !MARKET.matcher(r).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    return allowed.stream().filter(requested::contains).toList();
  }
}
