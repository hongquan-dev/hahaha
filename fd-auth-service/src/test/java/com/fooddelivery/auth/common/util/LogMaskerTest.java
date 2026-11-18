package com.fooddelivery.auth.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link LogMasker}: what stays visible of an email, a phone number and a login identifier, and the
 * values too short to keep anything.
 */
class LogMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "quan@gmail.com, q***@gmail.com",
            "q@gmail.com, q***@gmail.com",
            "quan.nguyen+food@mail.edu.vn, q***@mail.edu.vn"
    })
    void emailKeepsFirstCharacterAndDomain(String email, String masked) {
        assertThat(LogMasker.email(email)).isEqualTo(masked);
    }

    @Test
    void emailWithoutLocalPartIsFullyHidden() {
        assertThat(LogMasker.email("quan")).isEqualTo("***");
        assertThat(LogMasker.email("@gmail.com")).isEqualTo("***");
        assertThat(LogMasker.email(null)).isNull();
    }

    @Test
    void phoneKeepsMobilePrefixAndLastThreeDigits() {
        assertThat(LogMasker.phone("+84912345678")).isEqualTo("+8491****678");
    }

    @Test
    void phoneTooShortToHideAnythingIsFullyHidden() {
        assertThat(LogMasker.phone("12345678")).isEqualTo("***");
        assertThat(LogMasker.phone("")).isEqualTo("***");
        assertThat(LogMasker.phone(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "0912.345.678, +8491****678",
            "84912345678, +8491****678"
    })
    void phoneIdentifierIsMaskedAsAPhoneNumber(String identifier, String masked) {
        assertThat(LogMasker.identifier(identifier)).isEqualTo(masked);
    }

    @ParameterizedTest
    @CsvSource({
            "Quan.Nguyen, qu***",
            "' Quan@Gmail.com ', qu***",
            "' quan ', qu***",
            "abcd, ab***"
    })
    void otherIdentifierKeepsOnlyItsFirstTwoCharacters(String identifier, String masked) {
        assertThat(LogMasker.identifier(identifier)).isEqualTo(masked);
    }

    @ParameterizedTest
    @CsvSource({"Matkhau@123, ma***", "Quan2005!, qu***", "Abc@1234, ab***"})
    void passwordTypedAsIdentifierDoesNotReachTheLog(String password, String masked) {
        assertThat(LogMasker.identifier(password)).isEqualTo(masked).doesNotContain("@123", "2005", "1234");
    }

    @ParameterizedTest
    @CsvSource({"abc, ***", "ab, ***", "'', ***"})
    void identifierOfThreeCharactersOrFewerIsFullyHidden(String identifier, String masked) {
        assertThat(LogMasker.identifier(identifier)).isEqualTo(masked);
    }

    @Test
    void nullIdentifierStaysNull() {
        assertThat(LogMasker.identifier(null)).isNull();
    }
}
