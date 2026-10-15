package com.fooddelivery.auth.config;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "routes")
public class ApiConfigWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "route")
    private List<RouteXmlDto> routes;

    public List<RouteXmlDto> getRoutes() { return routes; }
    public void setRoutes(List<RouteXmlDto> routes) { this.routes = routes; }

    public static class RouteXmlDto {
        private String method;
        private String path;
        private String validator;
        private String schema;
        private String controller;

        // Getters and Setters
        public String getValidator() { return validator; }
        public void setValidator(String validator) { this.validator = validator; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getController() { return controller; }
        public void setController(String controller) { this.controller = controller; }
    }
}