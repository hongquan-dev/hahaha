package com.fooddelivery.auth.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link RegisterRequest#normalized()} with the examples of its comment. The phone formats themselves
 * are covered by {@code IdentifierNormalizerTest}.
 */
class RegisterRequestTest {

    @Test
    void usernameAndEmailAreTrimmedAndLowercasedAndPhoneBecomesPlus84() {
        RegisterRequest normalized =
                new RegisterRequest(" Quan.Nguyen ", "Abc@1234", " Quan@Gmail.com ", "0912.345.678").normalized();

        assertThat(normalized.username()).isEqualTo("quan.nguyen");
        assertThat(normalized.email()).isEqualTo("quan@gmail.com");
        assertThat(normalized.phoneNumber()).isEqualTo("+84912345678");
    }

    @Test
    void passwordIsKeptAsTyped() {
        RegisterRequest normalized =
                new RegisterRequest("quan", "  Abc@1234 ", "quan@gmail.com", "0912345678").normalized();

        assertThat(normalized.password()).isEqualTo("  Abc@1234 ");
    }

    @Test
    void nullFieldsStayNull() {
        assertThat(new RegisterRequest(null, null, null, null).normalized())
                .isEqualTo(new RegisterRequest(null, null, null, null));
    }

    @Test
    void normalizedValueIsUnchangedByNormalizingAgain() {
        RegisterRequest once = new RegisterRequest(" Quan ", "Abc@1234", " Q@Gmail.com ", "0912 345 678").normalized();

        assertThat(once.normalized()).isEqualTo(once);
    }
}
