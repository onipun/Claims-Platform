package com.chubb.claims.claim;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 of a canonical form of the submit request: keys sorted, null properties dropped, numbers
 * written without trailing zeros. The same request body always gives the same hash, however the client
 * ordered the fields or wrote "1200" or "1200.00".
 */
final class RequestHash {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private RequestHash() {}

  static String of(Object request) {
    StringBuilder sb = new StringBuilder();
    canonical(MAPPER.valueToTree(request), sb);
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void canonical(JsonNode n, StringBuilder sb) {
    if (n.isObject()) {
      Map<String, JsonNode> sorted = new TreeMap<>();
      n.properties().forEach(e -> { if (!e.getValue().isNull()) sorted.put(e.getKey(), e.getValue()); });
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        sb.append(MAPPER.writeValueAsString(e.getKey())).append(':');
        canonical(e.getValue(), sb);
      }
      sb.append('}');
    } else if (n.isArray()) {
      sb.append('[');
      boolean first = true;
      for (JsonNode child : n) {
        if (!first) sb.append(',');
        first = false;
        canonical(child, sb);
      }
      sb.append(']');
    } else if (n.isNumber()) {
      BigDecimal d = n.decimalValue();
      sb.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
    } else {
      sb.append(MAPPER.writeValueAsString(n));
    }
  }
}
