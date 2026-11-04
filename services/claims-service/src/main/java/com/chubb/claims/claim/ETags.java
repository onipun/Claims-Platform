package com.chubb.claims.claim;

import com.chubb.claims.web.ApiException;
import org.springframework.http.HttpStatus;

/** The ETag of a claim is its version, quoted: {@code "3"}. */
public final class ETags {
  private ETags() {}

  public static String of(long version) {
    return "\"" + version + "\"";
  }

  public static long parseVersion(String header) {
    String v = header.trim();
    if (v.startsWith("W/")) v = v.substring(2);
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH", "If-Match is not a valid ETag");
    }
  }
}
