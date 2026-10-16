package com.fooddelivery.auth.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

// Validator for the test APIs in TestController
@Component
public class TestValidator {
    private static final Logger log = LogManager.getLogger(TestValidator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Matches <validator>...TestValidator.validateItem</validator> in api-routes.xml
    // Body structure and data types were already checked against schemas/test-item.json
    public boolean validateItem(HttpServletRequest request, String requestBody) {
        JsonNode body = parseBody(requestBody);
        if (body == null) {
            return false;
        }

        // Every field in the body must have a value: no null, no blank string, no empty object/array
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (isEmpty(field.getValue())) {
                log.warn("[VALIDATE] Field '{}' must not be empty", field.getKey());
                return false;
            }
        }
        return true;
    }

    private boolean isEmpty(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        if (value.isTextual()) {
            return value.asText().isBlank();
        }
        return value.isContainerNode() && value.isEmpty();
    }

    private JsonNode parseBody(String requestBody) {
        try {
            return objectMapper.readTree(requestBody);
        } catch (JsonProcessingException e) {
            log.warn("[VALIDATE] Cannot parse request body: {}", e.getOriginalMessage());
            return null;
        }
    }
}
