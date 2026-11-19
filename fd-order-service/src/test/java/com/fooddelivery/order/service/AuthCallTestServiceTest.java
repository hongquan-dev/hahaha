package com.fooddelivery.order.service;

import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.dto.response.FieldErrorDetail;
import com.fooddelivery.order.client.AuthGrpcClient;
import com.fooddelivery.order.client.AuthUser;
import com.fooddelivery.order.dto.AuthCallResult;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthCallTestService} with {@link AuthGrpcClient} mocked by Mockito: the service returns the
 * client's user and lets the client's {@code BusinessException} through.
 *
 * <p>The service times the call with {@code System.nanoTime()}, not a {@code Clock}, so only a non-negative
 * {@code elapsedMs} is checked.
 */
class AuthCallTestServiceTest {

    private static final long USER_ID = 17939477151001L;
    private static final AuthUser USER = new AuthUser(USER_ID, "quan", "+84912345678", "CUSTOMER", true);

    private final AuthGrpcClient grpcClient = mock(AuthGrpcClient.class);
    private final AuthCallTestService service = new AuthCallTestService(grpcClient);

    @Test
    void callOverGrpcReturnsTheUserOfTheGrpcClient() {
        when(grpcClient.getUser(USER_ID)).thenReturn(USER);

        AuthCallResult result = service.callOverGrpc(USER_ID);

        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.elapsedMs()).isNotNegative();
    }

    @ParameterizedTest
    @CsvSource({
            "NOT_FOUND,           USER_NOT_FOUND",
            "SERVICE_UNAVAILABLE, AUTH_UNAVAILABLE",
            "BAD_GATEWAY,         AUTH_CALL_FAILED"
    })
    void businessExceptionOfTheClientPropagatesUnchanged(ApiStatus status, String code) {
        BusinessException thrown = new BusinessException(status, code, "Failed");
        when(grpcClient.getUser(USER_ID)).thenThrow(thrown);

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> service.callOverGrpc(USER_ID));

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
}
