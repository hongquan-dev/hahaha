package com.fooddelivery.auth.service.user;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.dto.InternalUserDto;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link UserQueryService} with a mocked repository.
 */
class UserQueryServiceTest {

    private static final long USER_ID = 17939477151001L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserQueryService service = new UserQueryService(userRepository);

    @Test
    void returnsTheUserWithoutSecrets() {
        User user = new User(USER_ID, "quan", "$2a$10$hash", "quan@test.com", "+84912345678", "CUSTOMER");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        InternalUserDto dto = service.getById(USER_ID);

        assertThat(dto).isEqualTo(new InternalUserDto(USER_ID, "quan", "+84912345678", "CUSTOMER", true));
    }

    @Test
    void lockedUserIsReturnedWithActiveFalse() {
        User user = new User(USER_ID, "quan", "$2a$10$hash", "quan@test.com", "+84912345678", "CUSTOMER");
        user.setActive(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.getById(USER_ID).active()).isFalse();
    }

    @Test
    void unknownIdThrows404UserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> service.getById(USER_ID));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.NOT_FOUND);
        assertThat(e.getCode()).isEqualTo("USER_NOT_FOUND");
    }
}
