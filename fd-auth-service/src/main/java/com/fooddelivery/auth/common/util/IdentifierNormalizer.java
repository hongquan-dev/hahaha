package com.fooddelivery.auth.common.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a username, email or phone number as typed by a user into the form stored in the {@code users} table, e.g.
 * {@code " Quan@Gmail.com "} -> {@code "quan@gmail.com"}, {@code "0912.345.678"} -> {@code "+84912345678"}.
 *
 * <p>Shared by registration ({@code RegisterRequest.normalized()}) and login ({@code AuthService.login}), so a value
 * accepted at registration is always found again at login. Null values stay null. Stateless, thread-safe.
 */
public final class IdentifierNormalizer {

    // Vietnamese mobile prefixes 03, 05, 07, 08, 09, after normalization to +84, e.g. "+84912345678"
    public static final Pattern PHONE = Pattern.compile("^\\+84[35789]\\d{8}$");

    private IdentifierNormalizer() {}

    // Trimmed and lowercased, for usernames and emails, e.g. " Quan.Nguyen " -> "quan.nguyen"
    public static String lowerTrim(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    // Removes spaces, ".", "-" and parentheses, then turns a leading "0" or "84" into "+84", e.g. "0912.345.678" ->
    // "+84912345678". A value it does not recognize is returned without separators, for the caller to reject
    public static String normalizePhone(String value) {
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

    // Whether a value already normalized by normalizePhone is a Vietnamese mobile number
    public static boolean isPhone(String normalizedPhone) {
        return normalizedPhone != null && PHONE.matcher(normalizedPhone).matches();
    }
}
