package com.fooddelivery.auth.service.auth;


import com.fooddelivery.auth.common.model.User;
import com.fooddelivery.auth.dto.LoginRequest;
import com.fooddelivery.auth.repository.UserRepository;
import com.fooddelivery.auth.service.session.IssuedTokens;
import com.fooddelivery.auth.service.session.SessionService;
import com.fooddelivery.auth.service.token.AccessTokenVerification;
import com.fooddelivery.auth.service.token.TokenService;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.status.ApiStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AuthService#login} and {@link AuthService#currentUser}, with mocked collaborators. The encoder
 * is a mock so the tests can check that BCrypt runs even when no account matches.
 */
class AuthServiceLoginTest {

    private static final String DUMMY_HASH = "$2a$10$dummy";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final TokenService tokenService = mock(TokenService.class);
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        authService = new AuthService(userRepository, passwordEncoder, sessionService, tokenService);
        user = new User(17939477151001L, "quan", "$2a$10$real", "quan@gmail.com", "+84912345678", "CUSTOMER");
        when(passwordEncoder.matches("Abc@12345", "$2a$10$real")).thenReturn(true);
        when(sessionService.start(any(), any(), any()))
                .thenReturn(new IssuedTokens("access", "refresh", Instant.now(), Instant.now()));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findFirstByPhoneNumber(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void emailIsLookedUpNormalized() {
        when(userRepository.findByEmail("quan@gmail.com")).thenReturn(Optional.of(user));

        LoginResult result = authService.login(new LoginRequest(" Quan@Gmail.com ", "Abc@12345"), "UA", "ip");

        assertThat(result.user().id()).isEqualTo(user.getId());
        assertThat(result.tokens().accessToken()).isEqualTo("access");
    }

    @Test
    void phoneIsLookedUpNormalized() {
        when(userRepository.findFirstByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));

        assertThat(authService.login(new LoginRequest("0912.345.678", "Abc@12345"), null, "ip").user().id())
                .isEqualTo(user.getId());
    }

    @Test
    void phoneLikeIdentifierFallsBackToUsername() {
        User digitsUser = new User(2L, "0912345678", "$2a$10$real", "d@test.com", "+84987654321", "CUSTOMER");
        when(userRepository.findByUsername("0912345678")).thenReturn(Optional.of(digitsUser));

        assertThat(authService.login(new LoginRequest("0912345678", "Abc@12345"), null, "ip").user().id())
                .isEqualTo(2L);
    }

    @Test
    void usernameIsLookedUpNormalized() {
        when(userRepository.findByUsername("quan")).thenReturn(Optional.of(user));

        assertThat(authService.login(new LoginRequest(" QUAN ", "Abc@12345"), null, "ip").user().id())
                .isEqualTo(user.getId());
    }

    @Test
    void unknownAccountRunsBcryptAgainstTheDummyHash() {
        assertError(() -> authService.login(new LoginRequest("nobody", "Abc@12345"), null, "ip"),
                ApiStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        verify(passwordEncoder).matches("Abc@12345", DUMMY_HASH);
        verify(sessionService, never()).start(any(), any(), any());
    }

    @Test
    void wrongPasswordGivesTheSameError() {
        when(userRepository.findByUsername("quan")).thenReturn(Optional.of(user));

        assertError(() -> authService.login(new LoginRequest("quan", "Wrong@123"), null, "ip"),
                ApiStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }

    @Test
    void disabledAccountIsReportedOnlyWithTheRightPassword() {
        user.setActive(false);
        when(userRepository.findByUsername("quan")).thenReturn(Optional.of(user));

        assertError(() -> authService.login(new LoginRequest("quan", "Wrong@123"), null, "ip"),
                ApiStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        assertError(() -> authService.login(new LoginRequest("quan", "Abc@12345"), null, "ip"),
                ApiStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        verify(sessionService, never()).start(any(), any(), any());
    }

    @Test
    void sessionIsStartedWithUserAgentAndIp() {
        when(userRepository.findByUsername("quan")).thenReturn(Optional.of(user));

        authService.login(new LoginRequest("quan", "Abc@12345"), "UA", "10.0.0.1");

        verify(sessionService).start(eq(user), eq("UA"), eq("10.0.0.1"));
    }

    @Test
    void currentUserMapsTokenOutcomes() {
        when(tokenService.verifyAccessToken("expired")).thenReturn(AccessTokenVerification.expired());
        when(tokenService.verifyAccessToken("bad")).thenReturn(AccessTokenVerification.invalid());
        when(tokenService.verifyAccessToken("ok")).thenReturn(AccessTokenVerification.valid(user.getId(), "CUSTOMER"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertError(() -> authService.currentUser("expired"), ApiStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
        assertError(() -> authService.currentUser("bad"), ApiStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(authService.currentUser("ok").username()).isEqualTo("quan");

        user.setActive(false);
        assertError(() -> authService.currentUser("ok"), ApiStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    private static void assertError(Runnable call, ApiStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }
}
