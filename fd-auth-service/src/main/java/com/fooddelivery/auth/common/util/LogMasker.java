package com.fooddelivery.auth.common.util;

/**
 * Hides most of an email or phone number before it is written to the log, e.g. {@code "quan@gmail.com"} ->
 * {@code "q***@gmail.com"}, {@code "+84912345678"} -> {@code "+8491****678"}: enough to tell accounts apart while
 * reading the log, not enough to contact the person. A username chosen at registration is logged as it is, but a
 * login identifier is masked too, since it may be a password typed in the wrong field.
 *
 * <p>Used by {@code AuthValidator} and {@code AuthService} at request time. Null values stay null. Stateless,
 * thread-safe.
 */
public final class LogMasker {

    private static final String HIDDEN = "***";

    // "+84" and the first two digits (the mobile prefix), then the last three digits, of a +84xxxxxxxxx number
    private static final int PHONE_VISIBLE_START = 5;
    private static final int PHONE_VISIBLE_END = 3;

    // Characters kept at the start of a login identifier that is not a phone number; a value of three characters or
    // fewer is fully hidden, since two of them would be most of it
    private static final int IDENTIFIER_VISIBLE_START = 2;

    private LogMasker() {}

    // First character of the local part and the whole domain, e.g. "quan@gmail.com" -> "q***@gmail.com". A value
    // without a local part before "@" is fully hidden, e.g. "quan" -> "***"
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return HIDDEN;
        }
        return email.charAt(0) + HIDDEN + email.substring(at);
    }

    // Mobile prefix and last three digits of a normalized number, e.g. "+84912345678" -> "+8491****678". A value
    // too short to keep anything hidden is fully hidden, e.g. "123" -> "***"
    public static String phone(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() <= PHONE_VISIBLE_START + PHONE_VISIBLE_END) {
            return HIDDEN;
        }
        return phone.substring(0, PHONE_VISIBLE_START) + "****" + phone.substring(phone.length() - PHONE_VISIBLE_END);
    }

    // Login identifier as typed, before any account matched it, so it may be a password typed in the wrong field. A
    // phone number is masked as such; anything else, emails included, keeps only its first two characters, e.g.
    // "0912.345.678" -> "+8491****678", " Quan@Gmail.com " -> "qu***", "Matkhau@123" -> "ma***". A valid password
    // never looks like a phone number: registration requires a letter and a special character
    public static String identifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String value = IdentifierNormalizer.lowerTrim(identifier);
        String phone = IdentifierNormalizer.normalizePhone(value);
        if (IdentifierNormalizer.isPhone(phone)) {
            return phone(phone);
        }
        return value.length() <= IDENTIFIER_VISIBLE_START + 1
                ? HIDDEN : value.substring(0, IDENTIFIER_VISIBLE_START) + HIDDEN;
    }
}
