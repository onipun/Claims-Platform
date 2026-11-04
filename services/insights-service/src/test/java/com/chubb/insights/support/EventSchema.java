package com.chubb.insights.support;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The committed event contract (what producers must satisfy), loaded from contracts/events. */
public final class EventSchema {
  public static final Path DIR = Path.of("..", "..", "contracts", "events");
  private static final Schema SCHEMA;

  static {
    try {
      SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(Files.readString(DIR.resolve("claim-event.v1.schema.json")), InputFormat.JSON);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private EventSchema() {}

  public static List<String> violations(String json) {
    List<Error> errors = SCHEMA.validate(json, InputFormat.JSON, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
    return errors.stream().map(Error::toString).toList();
  }
}
