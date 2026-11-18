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
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.hibernate.exception.ConstraintViolationException.ConstraintKind.NOT_NULL;
import static org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthService#register}, with a mocked {@link UserRepository} (no duplicates unless a test says
 * otherwise, {@code saveAndFlush} returns the entity it gets) and a real BCrypt encoder at the lowest cost, so the
 * stored hash can be checked against the raw password. A registration race is reproduced by making
 * {@code saveAndFlush} throw what Spring Data throws when PostgreSQL rejects the insert.
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
                mock(TokenService.class), mock(LoginThrottleService.class));
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

    @ParameterizedTest
    @CsvSource({
            "uk_users_username,     username,    Username is already taken",
            "uk_users_email,        email,       Email is already registered",
            "uk_users_phone_number, phoneNumber, Phone number is already registered",
            "UK_USERS_EMAIL,        email,       Email is already registered"})
    void uniqueConstraintViolationOnSaveGivesConflictWithThatField(String constraint, String field, String message) {
        // Another request saved the same value between the duplicate check and this save
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(integrityViolation(UNIQUE, constraint));

        BusinessException e = registerError();

        assertThat(e.getStatus()).isEqualTo(ApiStatus.CONFLICT);
        assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS");
        assertThat(e.getErrors()).containsExactly(new FieldErrorDetail(field, message));
    }

    @Test
    void otherIntegrityViolationOnSaveIsThrownAsItIs() {
        for (DataIntegrityViolationException error : List.of(
                integrityViolation(NOT_NULL, "users_username_not_null"),
                integrityViolation(UNIQUE, "pk_users"),
                integrityViolation(UNIQUE, null),
                new DataIntegrityViolationException("value too long for type character varying(255)"))) {
            when(userRepository.saveAndFlush(any(User.class))).thenThrow(error);

            assertThat(catchThrowable(() -> authService.register(REQUEST))).isSameAs(error);
        }
    }

    @Test
    void passwordIsHashedBeforeTheDatabaseIsQueried() {
        PasswordEncoder encoder = spy(passwordEncoder);
        authService = new AuthService(userRepository, encoder, mock(SessionService.class), mock(TokenService.class),
                mock(LoginThrottleService.class));

        authService.register(REQUEST);

        InOrder inOrder = inOrder(encoder, userRepository);
        inOrder.verify(encoder).encode(" Abc@1234 ");
        inOrder.verify(userRepository).existsByUsername("quan.nguyen");
    }

    // What Spring Data throws when the database rejects the insert, with Hibernate's violation as the cause
    private static DataIntegrityViolationException integrityViolation(ConstraintKind kind, String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("constraint violated", new SQLException("rejected"), kind,
                        constraint));
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
