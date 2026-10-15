package com.fooddelivery.auth.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Checks that a JSON request body has the structure described by a JSON Schema file:
 * correct data types (type), required fields (required), no unknown fields (additionalProperties: false),
 * nested objects (properties) and array elements (items). Value rules belong to the Validator layer.
 *
 * A field present with value null counts as missing, unless its type allows "null" (e.g. ["string", "null"]).
 */
public final class JsonSchemaUtil {
    // Reject trailing content after the JSON value (e.g. {"a":1} garbage), which Jackson ignores by default
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonSchemaUtil() {}

    // Load a schema file from the classpath (e.g. "schemas/login.json")
    public static JsonNode loadSchema(String classpathLocation) throws IOException {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            JsonNode schema = objectMapper.readTree(inputStream);
            if (schema == null || !schema.isObject()) {
                throw new IOException("Schema file must be a JSON object: " + classpathLocation);
            }
            return schema;
        }
    }

    // true if the body matches the schema, false otherwise (empty body, invalid JSON, wrong structure)
    public static boolean validate(JsonNode schema, String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return false;
        }
        try {
            return matches(schema, objectMapper.readTree(requestBody));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean matches(JsonNode schema, JsonNode data) {
        if (!matchesType(schema.get("type"), data)) {
            return false;
        }
        if (data.isObject()) {
            return matchesObject(schema, data);
        }
        if (data.isArray() && schema.has("items")) {
            for (JsonNode item : data) {
                if (!matches(schema.get("items"), item)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesObject(JsonNode schema, JsonNode data) {
        JsonNode properties = schema.path("properties");

        for (JsonNode field : schema.path("required")) {
            JsonNode value = data.get(field.asText());
            if (value == null || (value.isNull() && !allowsNull(properties.get(field.asText())))) {
                return false;
            }
        }

        boolean allowAdditional = schema.path("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode fieldSchema = properties.get(field.getKey());

            if (fieldSchema == null) {
                if (!allowAdditional) {
                    return false;
                }
            } else if (!field.getValue().isNull() && !matches(fieldSchema, field.getValue())) {
                // Null values were already handled by the "required" check
                return false;
            }
        }
        return true;
    }

    // "type" may be missing (any type), a single string ("string") or an array (["string", "null"])
    private static boolean matchesType(JsonNode typeNode, JsonNode data) {
        if (typeNode == null) {
            return true;
        }
        if (typeNode.isArray()) {
            for (JsonNode type : typeNode) {
                if (matchesType(type.asText(), data)) {
                    return true;
                }
            }
            return false;
        }
        return matchesType(typeNode.asText(), data);
    }

    private static boolean matchesType(String type, JsonNode data) {
        return switch (type) {
            case "object" -> data.isObject();
            case "array" -> data.isArray();
            case "string" -> data.isTextual();
            // Per JSON Schema spec, 1.0 is also an integer
            case "integer" -> data.isNumber() && data.decimalValue().stripTrailingZeros().scale() <= 0;
            case "number" -> data.isNumber();
            case "boolean" -> data.isBoolean();
            case "null" -> data.isNull();
            default -> false;
        };
    }

    // true only if the field's type explicitly includes "null"
    private static boolean allowsNull(JsonNode fieldSchema) {
        JsonNode typeNode = fieldSchema == null ? null : fieldSchema.get("type");
        return typeNode != null && matchesType(typeNode, NullNode.getInstance());
    }
}
