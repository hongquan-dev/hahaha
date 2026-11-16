package com.fooddelivery.auth.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests of {@link InternalUserValidator#validateGetUser} with the {@code userId} path variable as the API flow
 * extracts it.
 */
class InternalUserValidatorTest {

    private final InternalUserValidator validator = new InternalUserValidator();

    @Test
    void userIdPasses() {
        String userId = String.valueOf(IdUtils.generate(EntityType.USER));

        assertThatCode(() -> validator.validateGetUser(request(userId))).doesNotThrowAnyException();
    }

    @Test
    void malformedIdsAreRejectedOnTheUserIdField() {
        for (String userId : new String[] {"abc", "123", "179393137510011", "-1793931375100", "1793931375100x", ""}) {
            assertRejected(userId);
        }
    }

    @Test
    void idOfAnotherKindOfObjectIsRejected() {
        assertRejected(String.valueOf(IdUtils.generate(EntityType.ORDER)));
        // 11th digit 9: no EntityType has this code
        assertRejected("17939313759001");
    }

    private void assertRejected(String userId) {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> validator.validateGetUser(request(userId)));

        assertThat(e).as("userId=%s", userId).isNotNull();
        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_USER_ID");
        assertThat(e.getErrors()).singleElement().satisfies(error -> assertThat(error.field()).isEqualTo("userId"));
    }

    private static ApiRequest request(String userId) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/internal/users/" + userId);
        return new ApiRequest(servletRequest, Map.of("userId", userId), "");
    }
}
