package com.fooddelivery.auth.service.auth;

import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.session.SessionService;
import com.fooddelivery.auth.service.token.TokenService;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.dto.response.FieldErrorDetail;
import com.fooddelivery.id.entity.EntityType;
import com.fooddelivery.id.entity.IdUtils;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthService#register}, with a mocked {@link UserRepository} (no duplicates unless a test says
 * otherwise, {@code saveAndFlush} returns the entity it gets) and a real BCrypt encoder at the lowest cost, so the
 * stored hash can be checked against the raw password.
 */
class AuthServiceRegisterTest {

    private static final RegisterRequest REQUEST =
            new RegisterRequest(" Quan.Nguyen ", " Abc@1234 ", " Quan@Gmail.com ", "0912.345.678");

    private final UserRepository userRepository = mock(UserRepository.class);
    // Cost 4 instead of 10 keeps the tests fast; the hash format is the same
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        authService = new AuthService(userRepository, passwordEncoder, mock(SessionService.class),
                mock(TokenService.class));
    }

    @Test
    void savesAndReturnsTheNormalizedValues() {
        UserDto result = authService.register(REQUEST);

        User saved = savedUser();
        assertThat(saved.getUsername()).isEqualTo("quan.nguyen");
        assertThat(saved.getEmail()).isEqualTo("quan@gmail.com");
        assertThat(saved.getPhoneNumber()).isEqualTo("+84912345678");
        assertThat(result.id()).isEqualTo(saved.getId());
        assertThat(result.username()).isEqualTo("quan.nguyen");
        assertThat(result.email()).isEqualTo("quan@gmail.com");
        assertThat(result.phoneNumber()).isEqualTo("+84912345678");
    }

    @Test
    void storesABcryptHashOfThePasswordAsTyped() {
        authService.register(REQUEST);

        String hash = savedUser().getPassword();
        assertThat(hash).startsWith("$2").doesNotContain("Abc@1234");
        assertThat(passwordEncoder.matches(" Abc@1234 ", hash)).isTrue();
        assertThat(passwordEncoder.matches("Abc@1234", hash)).isFalse();
    }

    @Test
    void createsAnActiveCustomerWithAUserId() {
        UserDto result = authService.register(REQUEST);

        User saved = savedUser();
        assertThat(saved.getRole()).isEqualTo("CUSTOMER");
        assertThat(saved.isActive()).isTrue();
        assertThat(IdUtils.decodeObjectType(saved.getId())).isEqualTo(EntityType.USER);
        assertThat(result.role()).isEqualTo("CUSTOMER");
        assertThat(result.active()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "username,    Username is already taken",
            "email,       Email is already registered",
            "phoneNumber, Phone number is already registered"})
    void duplicateFieldGivesConflictWithThatFieldOnly(String field, String message) {
        switch (field) {
            case "username" -> when(userRepository.existsByUsername("quan.nguyen")).thenReturn(true);
            case "email" -> when(userRepository.existsByEmail("quan@gmail.com")).thenReturn(true);
            default -> when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(true);
        }

        BusinessException e = registerError();

        assertThat(e.getStatus()).isEqualTo(ApiStatus.CONFLICT);
        assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS");
        assertThat(e.getErrors()).containsExactly(new FieldErrorDetail(field, message));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void everyDuplicateFieldIsReportedTogether() {
        when(userRepository.existsByUsername("quan.nguyen")).thenReturn(true);
        when(userRepository.existsByEmail("quan@gmail.com")).thenReturn(true);
        when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(true);

        BusinessException e = registerError();

        assertThat(e.getStatus()).isEqualTo(ApiStatus.CONFLICT);
        assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS");
        assertThat(e.getErrors()).extracting(FieldErrorDetail::field, FieldErrorDetail::message).containsExactly(
                tuple("username", "Username is already taken"),
                tuple("email", "Email is already registered"),
                tuple("phoneNumber", "Phone number is already registered"));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void uniqueConstraintViolationOnSaveGivesConflictWithoutFieldErrors() {
        // Another request saved the same username or email between the duplicate check and this save
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        BusinessException e = registerError();

        assertThat(e.getStatus()).isEqualTo(ApiStatus.CONFLICT);
        assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS");
        assertThat(e.getErrors()).isEmpty();
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private BusinessException registerError() {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> authService.register(REQUEST));
        assertThat(e).as("BusinessException from register").isNotNull();
        return e;
    }
}
