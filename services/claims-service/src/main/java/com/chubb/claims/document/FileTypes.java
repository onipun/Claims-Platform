package com.chubb.claims.document;

import java.util.Optional;
import java.util.regex.Pattern;

/** Type is decided by the leading bytes, never by the declared content type or the extension (trap 10). */
final class FileTypes {
  static final int HEADER_BYTES = 8;
  private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

  private FileTypes() {}

  static Optional<String> detect(byte[] h, int n) {
    if (n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-') {
      return Optional.of("application/pdf");
    }
    if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0D
        && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    return Optional.empty();
  }

  /** The only extension each detected type may carry. */
  static String extensionFor(String contentType) {
    return switch (contentType) {
      case "application/pdf" -> ".pdf";
      case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg";
      default -> throw new IllegalArgumentException("unsupported type " + contentType);
    };
  }

  /**
   * The stored and served name: the sanitised client name with its extension replaced by the one for the
   * type detected from the bytes, so a PDF uploaded as {@code x.html} is served as {@code x.pdf}.
   * Dots left in the base are replaced too, so no inner extension survives.
   */
  static String displayName(String original, String detectedContentType) {
    String name = sanitiseFilename(original);
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : dot == 0 ? "" : name;
    base = base.replace('.', '_');
    if (base.isBlank()) base = "document";
    return base + extensionFor(detectedContentType);
  }

  /** Display name only: no directories, no odd characters, bounded length. Never used in a path. */
  static String sanitiseFilename(String original) {
    String name = original == null ? "" : original;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = UNSAFE.matcher(name).replaceAll("_");
    name = name.replaceAll("^\\.+", "");
    if (name.length() > 100) name = name.substring(name.length() - 100);
    return name.isBlank() ? "document" : name;
  }
}
