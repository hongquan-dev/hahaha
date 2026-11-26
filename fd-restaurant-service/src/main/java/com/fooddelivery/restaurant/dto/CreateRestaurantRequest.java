package com.fooddelivery.restaurant.dto;

import java.util.regex.Pattern;

/**
 * Body of {@code POST /restaurant/restaurants}, read with {@code request.getBody(CreateRestaurantRequest.class)}.
 *
 * <p>Checked in three steps: structure by {@code schemas/create-restaurant.json}, required fields and format by
 * {@code RestaurantValidator.validateCreate}, one restaurant per owner by {@code RestaurantService.create}. The owner
 * is not part of the body: it is the caller, from the {@code X-User-Id} header.
 *
 * @param name        2-100 characters once trimmed
 * @param address     5-255 characters once trimmed
 * @param phoneNumber Vietnamese mobile number in any usual form, e.g. {@code 0912.345.678} or {@code +84912345678}
 * @param description at most 1000 characters once trimmed; optional, {@code null} or blank means none
 */
public record CreateRestaurantRequest(
        String name,
        String address,
        String phoneNumber,
        String description
) {
    // Separators allowed inside a phone number: spaces, ".", "-" and parentheses
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s\\p{Z}.\\-()]");

    // Copy with name, address and description trimmed, a blank description as null, and the phone number as
    // +84xxxxxxxxx, e.g. " Phở Hà Nội " -> "Phở Hà Nội", "0912.345.678" -> "+84912345678". Null fields stay null
    public CreateRestaurantRequest normalized() {
        String trimmedDescription = description == null ? null : description.strip();
        return new CreateRestaurantRequest(name == null ? null : name.strip(),
                address == null ? null : address.strip(), normalizePhone(phoneNumber),
                trimmedDescription == null || trimmedDescription.isEmpty() ? null : trimmedDescription);
    }

    // Removes the separators, then turns a leading "0" or "84" into "+84", as auth-service does for accounts. A value
    // it does not recognize is returned without separators, for the validator to reject
    private static String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String digits = PHONE_SEPARATORS.matcher(value).replaceAll("");
        if (digits.startsWith("84") && digits.length() == 11) {
            return "+" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return "+84" + digits.substring(1);
        }
        return digits;
    }
}
