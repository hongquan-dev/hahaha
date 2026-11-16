package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.InternalUserDto;
import com.fooddelivery.auth.service.user.UserQueryService;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link InternalUserController} with a mocked {@link UserQueryService}: the {@code userId} path
 * variable reaches the service as a number and the user comes back with 200.
 */
class InternalUserControllerTest {

    private static final long USER_ID = 17939477151001L;

    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final InternalUserController controller = new InternalUserController(userQueryService);

    @Test
    void returnsTheUserOfThePathVariableWithOk() {
        InternalUserDto user = new InternalUserDto(USER_ID, "quan", "+84912345678", "CUSTOMER", true);
        when(userQueryService.getById(USER_ID)).thenReturn(user);

        ResponseEntity<ApiResponse<InternalUserDto>> response = controller.getUser(request(USER_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(user);
    }

    @Test
    void serviceErrorIsPassedOn() {
        BusinessException notFound = new BusinessException(ApiStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        when(userQueryService.getById(USER_ID)).thenThrow(notFound);

        assertThatThrownBy(() -> controller.getUser(request(USER_ID))).isSameAs(notFound);
    }

    private static ApiRequest request(long userId) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/internal/users/" + userId);
        return new ApiRequest(servletRequest, Map.of("userId", String.valueOf(userId)), "");
    }
}
