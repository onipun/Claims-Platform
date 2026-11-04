package com.chubb.claims.claim;

import com.chubb.claims.claim.domain.LossDetails;
import com.chubb.claims.claim.domain.MotorLoss;
import com.chubb.claims.claim.domain.PropertyLoss;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSONB column to the typed record for the claim's line. The "line" key picks the record. */
@Converter
public class LossDetailsConverter implements AttributeConverter<LossDetails, String> {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Override
  public String convertToDatabaseColumn(LossDetails loss) {
    if (loss == null) return null;
    ObjectNode node = MAPPER.valueToTree(loss);
    node.put("line", loss instanceof MotorLoss ? "MOTOR" : "PROPERTY");
    return MAPPER.writeValueAsString(node);
  }

  @Override
  public LossDetails convertToEntityAttribute(String json) {
    if (json == null) return null;
    ObjectNode node = (ObjectNode) MAPPER.readTree(json);
    JsonNode line = node.remove("line");
    return "MOTOR".equals(line == null ? null : line.asString())
        ? MAPPER.treeToValue(node, MotorLoss.class)
        : MAPPER.treeToValue(node, PropertyLoss.class);
  }
}
