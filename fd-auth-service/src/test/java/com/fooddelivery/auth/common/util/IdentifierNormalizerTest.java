package com.fooddelivery.auth.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link IdentifierNormalizer}, shared by registration and login.
 */
class IdentifierNormalizerTest {

    @Test
    void lowerTrimTrimsAndLowercases() {
        assertThat(IdentifierNormalizer.lowerTrim(" Quan@Gmail.com ")).isEqualTo("quan@gmail.com");
        assertThat(IdentifierNormalizer.lowerTrim(null)).isNull();
    }

    @Test
    void phoneFormsAllNormalizeToPlus84() {
        for (String typed : new String[] {"0912345678", "0912.345.678", "0912-345-678", "(091) 234 5678",
                "84912345678", "+84912345678", "+84 912 345 678"}) {
            assertThat(IdentifierNormalizer.normalizePhone(typed)).as(typed).isEqualTo("+84912345678");
        }
        assertThat(IdentifierNormalizer.normalizePhone(null)).isNull();
    }

    @Test
    void isPhoneAcceptsOnlyVietnameseMobilePrefixes() {
        assertThat(IdentifierNormalizer.isPhone("+84912345678")).isTrue();
        assertThat(IdentifierNormalizer.isPhone("+84312345678")).isTrue();
        assertThat(IdentifierNormalizer.isPhone("+84212345678")).isFalse();
        assertThat(IdentifierNormalizer.isPhone("+8491234567")).isFalse();
        assertThat(IdentifierNormalizer.isPhone(IdentifierNormalizer.normalizePhone("quan.nguyen"))).isFalse();
        assertThat(IdentifierNormalizer.isPhone(null)).isFalse();
    }
}
