package com.fooddelivery.order.service;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.dto.response.FieldErrorDetail;
import com.fooddelivery.order.client.AuthGrpcClient;
import com.fooddelivery.order.client.AuthHttpClient;
import com.fooddelivery.order.client.AuthUser;
import com.fooddelivery.order.dto.AuthCallResult;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthCallTestService} with {@link AuthHttpClient} and {@link AuthGrpcClient} mocked by Mockito:
 * each transport uses its own client, labels the result and lets the client's {@code BusinessException} through.
 *
 * <p>The service times the call with {@code System.nanoTime()}, not a {@code Clock}, so only a non-negative
 * {@code elapsedMs} is checked.
 */
class AuthCallTestServiceTest {

    private static final long USER_ID = 17939477151001L;
    private static final AuthUser USER = new AuthUser(USER_ID, "quan", "+84912345678", "CUSTOMER", true);

    private final AuthHttpClient httpClient = mock(AuthHttpClient.class);
    private final AuthGrpcClient grpcClient = mock(AuthGrpcClient.class);
    private final AuthCallTestService service = new AuthCallTestService(httpClient, grpcClient);

    @Test
    void callOverHttpUsesTheHttpClientAndReportsHttp() {
        when(httpClient.getUser(USER_ID)).thenReturn(USER);

        AuthCallResult result = service.callOverHttp(USER_ID);

        assertThat(result.transport()).isEqualTo("HTTP");
        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.elapsedMs()).isNotNegative();
        verifyNoInteractions(grpcClient);
    }

    @Test
    void callOverGrpcUsesTheGrpcClientAndReportsGrpc() {
        when(grpcClient.getUser(USER_ID)).thenReturn(USER);

        AuthCallResult result = service.callOverGrpc(USER_ID);

        assertThat(result.transport()).isEqualTo("gRPC");
        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.elapsedMs()).isNotNegative();
        verifyNoInteractions(httpClient);
    }

    @ParameterizedTest
    @CsvSource({
            "HTTP, NOT_FOUND,           USER_NOT_FOUND",
            "HTTP, SERVICE_UNAVAILABLE, AUTH_UNAVAILABLE",
            "HTTP, BAD_GATEWAY,         AUTH_CALL_FAILED",
            "gRPC, NOT_FOUND,           USER_NOT_FOUND",
            "gRPC, SERVICE_UNAVAILABLE, AUTH_UNAVAILABLE",
            "gRPC, BAD_GATEWAY,         AUTH_CALL_FAILED"
    })
    void businessExceptionOfTheClientPropagatesUnchanged(String transport, ApiStatus status, String code) {
        BusinessException thrown = new BusinessException(status, code, "Failed");
        LongFunction<AuthCallResult> call = stubFailure(transport, thrown);

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> call.apply(USER_ID));

        assertThat(e).isSameAs(thrown);
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getCode()).isEqualTo(code);
    }

    @Test
    void fieldErrorsOfTheClientExceptionAreKept() {
        List<FieldErrorDetail> errors = List.of(new FieldErrorDetail("userId", "Invalid user ID"));
        when(grpcClient.getUser(USER_ID))
                .thenThrow(new BusinessException(ApiStatus.BAD_REQUEST, "INVALID_USER_ID", "Invalid", errors));

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> service.callOverGrpc(USER_ID));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_USER_ID");
        assertThat(e.getErrors()).containsExactly(new FieldErrorDetail("userId", "Invalid user ID"));
    }

    // Makes the client of the transport throw, and returns the service method of that transport
    private LongFunction<AuthCallResult> stubFailure(String transport, BusinessException thrown) {
        if (transport.equals("HTTP")) {
            when(httpClient.getUser(USER_ID)).thenThrow(thrown);
            return service::callOverHttp;
        }
        when(grpcClient.getUser(USER_ID)).thenThrow(thrown);
        return service::callOverGrpc;
    }
}
