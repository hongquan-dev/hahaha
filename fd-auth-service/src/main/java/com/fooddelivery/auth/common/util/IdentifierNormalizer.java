package com.fooddelivery.auth.common.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a username, email or phone number as typed by a user into the form stored in the {@code users} table, e.g.
 * {@code " Quan@Gmail.com "} -> {@code "quan@gmail.com"}, {@code "0912.345.678"} -> {@code "+84912345678"}. Spaces
 * include the Unicode ones such as the no-break space U+00A0, which {@code String.trim()} and {@code String.strip()}
 * keep.
 *
 * <p>Shared by registration ({@code RegisterRequest.normalized()}) and login ({@code AuthService.login}), so a value
 * accepted at registration is always found again at login. Null values stay null. Stateless, thread-safe.
 */
public final class IdentifierNormalizer {

    // Vietnamese mobile prefixes 03, 05, 07, 08, 09, after normalization to +84, e.g. "+84912345678"
    public static final Pattern PHONE = Pattern.compile("^\\+84[35789]\\d{8}$");

    // Leading or trailing spaces of any kind. \p{Z} adds the Unicode spaces that \s, String.trim() and String.strip()
    // all keep, e.g. the no-break space U+00A0 pasted from a web page or a document
    private static final Pattern EDGE_SPACES = Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");

    // Separators allowed inside a phone number: spaces of any kind, ".", "-" and parentheses
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s\\p{Z}.\\-()]");

    private IdentifierNormalizer() {}

    // Without leading or trailing spaces of any kind, e.g. " quan " -> "quan". Null stays null
    public static String strip(String value) {
        return value == null ? null : EDGE_SPACES.matcher(value).replaceAll("");
    }

    // Stripped and lowercased, for usernames and emails, e.g. " Quan.Nguyen " -> "quan.nguyen"
    public static String lowerTrim(String value) {
        return value == null ? null : strip(value).toLowerCase(Locale.ROOT);
    }

    // Removes the separators, then turns a leading "0" or "84" into "+84", e.g. "0912.345.678" -> "+84912345678". A
    // value it does not recognize is returned without separators, for the caller to reject
    public static String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String digits = PHONE_SEPARATORS.matcher(value).replaceAll("");
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
