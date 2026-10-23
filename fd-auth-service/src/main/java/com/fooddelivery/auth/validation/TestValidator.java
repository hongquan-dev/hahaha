package com.fooddelivery.auth.validation;

import com.fooddelivery.apiflow.validation.ValidationRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

// Validator for the test APIs in TestController
@Component
public class TestValidator {
    private static final Logger log = LogManager.getLogger(TestValidator.class);

    // Matches <validator>...TestValidator.validateItem</validator> in api-routes.xml
    // No checks: only logs what the validator receives from the request, then lets it through
    public boolean validateItem(ValidationRequest request) {
        log.info("[VALIDATE] ===== [{}] {} =====", request.getMethod(), request.getPath());
        log.info("[VALIDATE] Path variables : {}", request.getPathVariables());
        log.info("[VALIDATE] Query params   : {}", request.getQueryParams());
        request.getHeaders().forEach((name, values) -> log.info("[VALIDATE] Header         : {} = {}", name, values));
        log.info("[VALIDATE] Body (raw)     : {}", request.getBody());
        log.info("[VALIDATE] Body (json)    : {}", request.getBodyJson());
        return true;
    }
}
