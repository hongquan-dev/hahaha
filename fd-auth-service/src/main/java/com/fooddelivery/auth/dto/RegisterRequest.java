package com.fooddelivery.auth.dto;

import java.util.Locale;

/**
 * Body of {@code POST /auth/register}, read with {@code request.getBody(RegisterRequest.class)}.
 *
 * <p>Checked in three steps: structure by {@code schemas/register.json}, required fields and format by
 * {@code AuthValidator.validateRegister}, duplicates with existing accounts by {@code AuthService.register}. Each step
 * works on {@link #normalized()}, so the value that is checked is the value that is stored.
 *
 * @param username    3-30 characters of {@code a-z}, {@code 0-9}, {@code .} and {@code _}, case-insensitive
 * @param password    8-64 characters with an uppercase letter, a lowercase letter, a digit and a special character;
 *                    never trimmed
 * @param email       case-insensitive
 * @param phoneNumber Vietnamese mobile number as {@code 0xxx}, {@code 84xxx} or {@code +84xxx}; spaces, {@code .},
 *                    {@code -} and parentheses are allowed, e.g. {@code 0912.345.678}
 */
public record RegisterRequest(
        String username,
        String password,
        String email,
        String phoneNumber
) {
    // Copy with username and email trimmed and lowercased, and the phone number as +84xxxxxxxxx, e.g.
    // " Quan@Gmail.com " -> "quan@gmail.com", "0912.345.678" -> "+84912345678". Null fields stay null
    public RegisterRequest normalized() {
        return new RegisterRequest(lowerTrim(username), password, lowerTrim(email), normalizePhone(phoneNumber));
    }

    private static String lowerTrim(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    // Removes separators, then turns a leading "0" or "84" into "+84". A value it does not recognize is returned
    // without separators, for the validator to reject
    private static String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("+84")) {
            return digits;
        }
        if (digits.startsWith("84") && digits.length() == 11) {
            return "+" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return "+84" + digits.substring(1);
        }
        return digits;
    }
}
