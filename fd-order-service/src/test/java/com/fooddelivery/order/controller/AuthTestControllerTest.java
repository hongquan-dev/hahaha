package com.fooddelivery.order.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiResponse;
import com.fooddelivery.order.client.AuthUser;
import com.fooddelivery.order.dto.AuthCallResult;
import com.fooddelivery.order.service.AuthCallTestService;
import com.fooddelivery.order.validation.AuthTestValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthTestController} with {@link AuthCallTestService} mocked by Mockito and an
 * {@link ApiRequest} built on a {@code MockHttpServletRequest}: the controller passes the {@code X-User-Id} header to
 * the service method of its transport and returns the result with 200. The header itself is checked by
 * {@code AuthTestValidatorTest}.
 *
 * <p>Only one method is stubbed, for {@code USER_ID}; any other call returns {@code null}, so a body holding the
 * stubbed result proves the right method got the right user ID.
 */
class AuthTestControllerTest {

    private static final long USER_ID = 17939477151001L;
    private static final AuthUser USER = new AuthUser(USER_ID, "quan", "+84912345678", "CUSTOMER", true);

    private final AuthCallTestService service = mock(AuthCallTestService.class);
    private final AuthTestController controller = new AuthTestController(service);

    @Test
    void authOverHttpCallsTheHttpServiceMethodWithTheHeaderUserIdAndReturns200() {
        AuthCallResult result = new AuthCallResult("HTTP", 12, USER);
        when(service.callOverHttp(USER_ID)).thenReturn(result);

        ResponseEntity<ApiResponse<AuthCallResult>> response =
                controller.authOverHttp(request("/order/test/auth-http"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(result);
    }

    @Test
    void authOverGrpcCallsTheGrpcServiceMethodWithTheHeaderUserIdAndReturns200() {
        AuthCallResult result = new AuthCallResult("gRPC", 3, USER);
        when(service.callOverGrpc(USER_ID)).thenReturn(result);

        ResponseEntity<ApiResponse<AuthCallResult>> response =
                controller.authOverGrpc(request("/order/test/auth-grpc"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(result);
    }

    // A request as the gateway forwards it, with X-User-Id already checked by AuthTestValidator
    private static ApiRequest request(String path) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
        servletRequest.addHeader(AuthTestValidator.USER_ID_HEADER, String.valueOf(USER_ID));
        return new ApiRequest(servletRequest, Map.of(), "");
    }
}
