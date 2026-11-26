package com.fooddelivery.restaurant.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link CreateRestaurantRequest#normalized()}: trimming, blank description as {@code null}, phone
 * numbers in the usual Vietnamese forms turned into {@code +84xxxxxxxxx}, null fields kept null.
 */
class CreateRestaurantRequestTest {

    @Test
    void textFieldsAreTrimmed() {
        CreateRestaurantRequest result = new CreateRestaurantRequest(" Phở Hà Nội ", "\t12 Lý Thái Tổ ",
                "0912345678", " Phở bò gia truyền ").normalized();

        assertThat(result.name()).isEqualTo("Phở Hà Nội");
        assertThat(result.address()).isEqualTo("12 Lý Thái Tổ");
        assertThat(result.description()).isEqualTo("Phở bò gia truyền");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankDescriptionBecomesNull(String description) {
        assertThat(new CreateRestaurantRequest("Phở", "12 Lý Thái Tổ", "0912345678", description).normalized()
                .description()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"0912345678|+84912345678", "0912.345.678|+84912345678",
            "(091) 234-5678|+84912345678", "84912345678|+84912345678", "+84912345678|+84912345678"})
    void phoneNumberBecomesPlus84(String phoneNumber, String expected) {
        assertThat(new CreateRestaurantRequest("Phở", "12 Lý Thái Tổ", phoneNumber, null).normalized().phoneNumber())
                .isEqualTo(expected);
    }

    // Left for the validator to reject
    @Test
    void unrecognizedPhoneNumberKeepsItsDigitsOnly() {
        assertThat(new CreateRestaurantRequest("Phở", "12 Lý Thái Tổ", "091.234", null).normalized().phoneNumber())
                .isEqualTo("091234");
    }

    @Test
    void nullFieldsStayNull() {
        CreateRestaurantRequest result = new CreateRestaurantRequest(null, null, null, null).normalized();

        assertThat(result).isEqualTo(new CreateRestaurantRequest(null, null, null, null));
    }
}
