package com.fooddelivery.order.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests of {@link AuthTestValidator#validateCaller} with the {@code X-User-Id} header as the gateway sets it, or
 * as a caller bypassing the gateway might send it.
 */
class AuthTestValidatorTest {

    private final AuthTestValidator validator = new AuthTestValidator();

    @Test
    void headerSetByTheGatewayPasses() {
        assertThatCode(() -> validator.validateCaller(request("17939477151001"))).doesNotThrowAnyException();
    }

    @Test
    void missingOrMalformedHeaderIs401() {
        for (String userId : new String[] {null, "", "abc", "123", "179394771510011"}) {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> validator.validateCaller(request(userId)));

            assertThat(e).as("X-User-Id=%s", userId).isNotNull();
            assertThat(e.getStatus()).isEqualTo(ApiStatus.UNAUTHORIZED);
            assertThat(e.getCode()).isEqualTo("UNAUTHENTICATED");
        }
    }

    private static ApiRequest request(String userId) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/order/test/auth-http");
        if (userId != null) {
            servletRequest.addHeader(AuthTestValidator.USER_ID_HEADER, userId);
        }
        return new ApiRequest(servletRequest, Map.of(), "");
    }
}
