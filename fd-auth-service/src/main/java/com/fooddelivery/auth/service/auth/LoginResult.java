package com.fooddelivery.auth.service.auth;

import com.fooddelivery.auth.dto.UserDto;
import com.fooddelivery.auth.service.session.IssuedTokens;

/**
 * Result of {@link AuthService#login}: the user for the response body and the tokens for the cookies. Internal to the
 * service and controller, never serialized as a whole, so the tokens never reach the body.
 *
 * @param user   logged-in user, returned in the body
 * @param tokens tokens of the new session, set as cookies
 */
public record LoginResult(UserDto user, IssuedTokens tokens) {
}
