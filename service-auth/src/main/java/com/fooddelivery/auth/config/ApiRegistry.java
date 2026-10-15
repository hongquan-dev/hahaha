package com.fooddelivery.auth.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fooddelivery.auth.util.JsonSchemaUtil;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Component
public class ApiRegistry {
    private static final Logger log = LogManager.getLogger(ApiRegistry.class);

    private final Map<String, RouteDefinition> routes = new HashMap<>();

    @Value("${app.api-routes.location}")
    private String apiRoutesLocation;

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource(apiRoutesLocation);
            InputStream inputStream = resource.getInputStream();

            XmlMapper xmlMapper = new XmlMapper();
            ApiConfigWrapper config = xmlMapper.readValue(inputStream, ApiConfigWrapper.class);

            if (config != null && config.getRoutes() != null) {
                for (ApiConfigWrapper.RouteXmlDto dto : config.getRoutes()) {
                    String key = dto.getMethod().toUpperCase() + ":" + dto.getPath();

                    String schemaPath = dto.getSchema();
                    JsonNode schemaNode = null;
                    if (schemaPath != null && !schemaPath.trim().isEmpty()) {
                        schemaPath = schemaPath.trim();
                        schemaNode = JsonSchemaUtil.loadSchema(schemaPath);
                    } else {
                        schemaPath = null;
                    }

                    routes.put(key, new RouteDefinition(
                            dto.getMethod(),
                            dto.getPath(),
                            dto.getValidator(),
                            schemaPath,
                            schemaNode,
                            dto.getController()
                    ));
                }
            }
        } catch (Exception e) {
            log.error("Failed to read API routes config file '{}': {}", apiRoutesLocation, e.getMessage(), e);
            throw new IllegalStateException("Unable to load API routes config: " + apiRoutesLocation, e);
        }
    }

    public RouteDefinition getRoute(String method, String path) {
        return routes.get(method.toUpperCase() + ":" + path);
    }

    public static class RouteDefinition {
        private String method;
        private String path;
        private String validator;
        private String schemaPath;
        private JsonNode schema;
        private String controllerName;

        public RouteDefinition(String method, String path, String validator, String schemaPath, JsonNode schema, String controllerName) {
            this.method = method;
            this.path = path;
            this.validator = validator;
            this.schemaPath = schemaPath;
            this.schema = schema;
            this.controllerName = controllerName;
        }

        public String getMethod() { return method; }
        public String getPath() { return path; }
        public String getValidator() { return validator; }
        public String getSchemaPath() { return schemaPath; }
        public JsonNode getSchema() { return schema; }
        public String getControllerName() { return controllerName; }
    }
}
