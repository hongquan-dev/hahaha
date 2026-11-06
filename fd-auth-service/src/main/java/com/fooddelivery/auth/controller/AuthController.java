package com.fooddelivery.auth.controller;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.auth.dto.LoginRequest;
import com.fooddelivery.auth.dto.RegisterRequest;
import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.service.auth.AuthService;
import com.fooddelivery.auth.service.auth.LoginResult;
import com.fooddelivery.auth.service.session.IssuedTokens;
import com.fooddelivery.auth.service.session.SessionService;
import com.fooddelivery.auth.web.AuthCookies;
import com.fooddelivery.web.exception.BusinessException;
import com.fooddelivery.web.handler.ResponseHandler;
import com.fooddelivery.web.model.ApiResponse;
import com.fooddelivery.web.status.ApiStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Account and session APIs of the auth service, under {@code /auth}.
 *
 * <p>Each method receives the same {@code ApiRequest} instance that the route's validator checked, so values read from
 * it are already valid; the business rules are left to {@link AuthService} and {@link SessionService}. Tokens only
 * travel in the {@code access_token} and {@code refresh_token} cookies built by {@link AuthCookies}, never in a
 * response body.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final AuthCookies authCookies;

    public AuthController(AuthService authService, SessionService sessionService, AuthCookies authCookies) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.authCookies = authCookies;
    }

    /**
     * {@code POST /auth/register}: creates an account and returns it with 201.
     *
     * <p>Flow: {@code schemas/register.json} (structure) -> {@code AuthValidator.validateRegister} (required fields,
     * format) -> this method -> {@link AuthService#register} (duplicates, save). The user is not logged in: no cookie
     * is set, the client calls the login API afterwards.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserDto>> register(ApiRequest request) {
        UserDto user = authService.register(request.getBody(RegisterRequest.class));
        return ResponseHandler.generateResponse(ApiStatus.CREATED, ApiResponse.success(user));
    }

    /**
     * {@code POST /auth/login}: logs in with a username, email or phone number and returns the user with 200, plus the
     * {@code access_token} and {@code refresh_token} cookies of a new session.
     *
     * <p>Flow: {@code OriginCheckFilter} (CSRF) -> {@code schemas/login.json} (structure) ->
     * {@code AuthValidator.validateLogin} (required fields, length) -> this method -> {@link AuthService#login}
     * (account, password, session).
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserDto>> login(ApiRequest request) {
        LoginResult result = authService.login(request.getBody(LoginRequest.class), userAgent(request),
                request.getRemoteAddr());
        return ResponseHandler.generateResponse(ApiStatus.OK, tokenCookies(result.tokens()),
                ApiResponse.success(result.user()));
    }

    /**
     * {@code GET /auth/me}: returns the user of the {@code access_token} cookie with 200, so the client can tell
     * whether it is logged in (it cannot read the HttpOnly cookie itself).
     *
     * <p>Flow: this method -> {@link AuthService#currentUser} (token, user). 401 {@code TOKEN_EXPIRED} or
     * {@code UNAUTHENTICATED} otherwise; the client then calls {@code POST /auth/refresh} once.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> me(ApiRequest request) {
        UserDto user = authService.currentUser(request.getCookie(AuthCookies.ACCESS_TOKEN_COOKIE).asString());
        return ResponseHandler.generateResponse(ApiStatus.OK, ApiResponse.success(user));
    }

    /**
     * {@code POST /auth/refresh}: rotates the session of the {@code refresh_token} cookie and returns 200 with no data
     * and both cookies renewed. No body.
     *
     * <p>Flow: {@code OriginCheckFilter} (CSRF) -> this method -> {@link SessionService#refresh} (token, rotation).
     * On a 401 ({@code REFRESH_TOKEN_INVALID}, {@code REFRESH_TOKEN_REUSED}) both cookies are cleared, since the
     * session is over. A 409 {@code REFRESH_CONFLICT} leaves them alone: another tab has just set new ones.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(ApiRequest request) {
        try {
            IssuedTokens tokens = sessionService.refresh(
                    request.getCookie(AuthCookies.REFRESH_TOKEN_COOKIE).asString(), userAgent(request),
                    request.getRemoteAddr());
            return ResponseHandler.generateResponse(ApiStatus.OK, tokenCookies(tokens), ApiResponse.ok());
        } catch (BusinessException e) {
            if (e.getStatus() != ApiStatus.UNAUTHORIZED) {
                throw e;
            }
            return ResponseHandler.generateErrorResponse(e.getStatus(), clearCookies(), e.getCode(), e.getMessage(),
                    e.getErrors());
        }
    }

    /**
     * {@code POST /auth/logout}: ends the session of the {@code refresh_token} cookie and clears both cookies. Always
     * 200 with no data, even without a valid cookie, so the client can never stay half logged out. No body.
     *
     * <p>Flow: {@code OriginCheckFilter} (CSRF) -> this method -> {@link SessionService#end} (revokes the session).
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(ApiRequest request) {
        sessionService.end(request.getCookie(AuthCookies.REFRESH_TOKEN_COOKIE).asString());
        return ResponseHandler.generateResponse(ApiStatus.OK, clearCookies(), ApiResponse.ok());
    }

    private HttpHeaders tokenCookies(IssuedTokens tokens) {
        return AuthCookies.headersOf(authCookies.accessCookie(tokens.accessToken()),
                authCookies.refreshCookie(tokens.refreshToken(), tokens.expiresAt(), tokens.familyExpiresAt()));
    }

    private HttpHeaders clearCookies() {
        return AuthCookies.headersOf(authCookies.clearAccessCookie(), authCookies.clearRefreshCookie());
    }

    private static String userAgent(ApiRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT).asString();
    }
}
