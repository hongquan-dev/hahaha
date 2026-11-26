package com.fooddelivery.restaurant.validation;

import com.fooddelivery.apiflow.request.ApiRequest;
import com.fooddelivery.dto.response.ApiStatus;
import com.fooddelivery.dto.response.FieldErrorDetail;
import com.fooddelivery.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Unit tests of {@link RestaurantValidator#validateCreate}, with the JSON body wrapped in an {@link ApiRequest} as the
 * API flow builds it. Each invalid case checks the status, the error code and the field errors the form shows.
 */
class RestaurantValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String NAME = "Phở Hà Nội";
    private static final String ADDRESS = "12 Lý Thái Tổ, Hoàn Kiếm";
    private static final String PHONE = "0912345678";

    private final RestaurantValidator validator = new RestaurantValidator();

    @Test
    void validBodyPasses() {
        assertPasses(body(NAME, ADDRESS, PHONE, "Phở bò gia truyền"));
    }

    @Test
    void descriptionIsOptional() {
        assertPasses(body(NAME, ADDRESS, PHONE, null));
    }

    // Lengths are checked after trimming
    @ParameterizedTest
    @ValueSource(ints = {2, 100})
    void nameAtTheLengthLimitsPasses(int length) {
        assertPasses(body(" " + "a".repeat(length) + " ", ADDRESS, PHONE, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 255})
    void addressAtTheLengthLimitsPasses(int length) {
        assertPasses(body(NAME, "a".repeat(length), PHONE, null));
    }

    @Test
    void descriptionAtTheLengthLimitPasses() {
        assertPasses(body(NAME, ADDRESS, PHONE, "a".repeat(1000)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912.345.678", "+84912345678", "0312345678", "0587654321"})
    void vietnameseMobileNumbersInUsualFormsPass(String phoneNumber) {
        assertPasses(body(NAME, ADDRESS, phoneNumber, null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingNameIsRejected(String name) {
        assertRejected(body(name, ADDRESS, PHONE, null), "name", "Name is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 101})
    void nameOutsideTheLengthLimitsIsRejected(int length) {
        assertRejected(body("a".repeat(length), ADDRESS, PHONE, null), "name", "Name must be 2-100 characters");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingAddressIsRejected(String address) {
        assertRejected(body(NAME, address, PHONE, null), "address", "Address is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 256})
    void addressOutsideTheLengthLimitsIsRejected(int length) {
        assertRejected(body(NAME, "a".repeat(length), PHONE, null), "address", "Address must be 5-255 characters");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingPhoneNumberIsRejected(String phoneNumber) {
        assertRejected(body(NAME, ADDRESS, phoneNumber, null), "phoneNumber", "Phone number is required");
    }

    // Landline, wrong mobile prefix, too short, too long, letters
    @ParameterizedTest
    @ValueSource(strings = {"02838123456", "0112345678", "091234567", "09123456789", "abc"})
    void phoneNumberThatIsNotAVietnameseMobileIsRejected(String phoneNumber) {
        assertRejected(body(NAME, ADDRESS, phoneNumber, null), "phoneNumber",
                "Phone number must be a Vietnamese mobile number");
    }

    @Test
    void tooLongDescriptionIsRejected() {
        assertRejected(body(NAME, ADDRESS, PHONE, "a".repeat(1001)), "description",
                "Description must be at most 1000 characters");
    }

    @Test
    void everyInvalidFieldIsReportedTogether() {
        BusinessException e = rejection(body("", "", "", "a".repeat(1001)));

        assertThat(e.getErrors()).extracting(FieldErrorDetail::field, FieldErrorDetail::message).containsExactly(
                tuple("name", "Name is required"),
                tuple("address", "Address is required"),
                tuple("phoneNumber", "Phone number is required"),
                tuple("description", "Description must be at most 1000 characters"));
    }

    private static String body(String name, String address, String phoneNumber, String description) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("address", address);
        fields.put("phoneNumber", phoneNumber);
        fields.put("description", description);
        return JSON.writeValueAsString(fields);
    }

    private static ApiRequest request(String body) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/restaurant/restaurants");
        servletRequest.addHeader("X-User-Id", "17939522301001");
        return new ApiRequest(servletRequest, Map.of(), body);
    }

    private void assertPasses(String body) {
        assertThatCode(() -> validator.validateCreate(request(body))).doesNotThrowAnyException();
    }

    private void assertRejected(String body, String field, String message) {
        BusinessException e = rejection(body);

        assertThat(e.getErrors()).containsExactly(new FieldErrorDetail(field, message));
    }

    private BusinessException rejection(String body) {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> validator.validateCreate(request(body)));
        assertThat(e).as("BusinessException from validateCreate").isNotNull();
        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_RESTAURANT_INFO");
        return e;
    }
}
