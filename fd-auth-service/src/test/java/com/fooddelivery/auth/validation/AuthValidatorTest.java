package com.fooddelivery.auth.validation;

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
 * Unit tests of {@link AuthValidator#validateRegister} and {@link AuthValidator#validateLogin}, with the JSON body
 * wrapped in an {@link ApiRequest} as the API flow builds it. Each invalid case checks the status, the error code and
 * the field errors the form shows.
 */
class AuthValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String USERNAME = "quan.nguyen_1";
    private static final String PASSWORD = "Abc@1234";
    private static final String EMAIL = "quan@gmail.com";
    private static final String PHONE = "0912345678";

    private static final String USERNAME_FORMAT =
            "Username must be 3-30 characters of letters, digits, '.' or '_', starting with a letter";
    private static final String PASSWORD_LENGTH = "Password must be 8-64 characters";
    private static final String PASSWORD_COMPOSITION =
            "Password must contain an uppercase letter, a lowercase letter, a digit and a special character";
    private static final String EMAIL_INVALID = "Email is invalid";
    private static final String PHONE_INVALID = "Phone number must be a Vietnamese mobile number";

    private final AuthValidator validator = new AuthValidator();

    @Test
    void validRegisterBodyPasses() {
        assertRegisterPasses(USERNAME, PASSWORD, EMAIL, PHONE);
    }

    @Test
    void registerValuesAreCheckedAfterNormalization() {
        assertRegisterPasses(" Quan.Nguyen ", PASSWORD, " Quan@Gmail.com ", "0912.345.678");
    }

    @Test
    void unicodeSpacesPastedAroundRegisterValuesAreIgnored() {
        assertRegisterPasses(" Quan.Nguyen ", PASSWORD, "quan@gmail.com ", "0912 345 678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912.345.678", "0912 345 678", "(091) 234-5678", "84912345678", "+84912345678",
            "+84 912 345 678", "0312345678", "0512345678", "0712345678", "0812345678"})
    void vietnameseMobileNumbersInAnyAcceptedFormPass(String phoneNumber) {
        assertRegisterPasses(USERNAME, PASSWORD, EMAIL, phoneNumber);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingUsernameIsRequired(String username) {
        assertRegisterRejected(register(username, PASSWORD, EMAIL, PHONE), "username", "Username is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "a.b", "abcdefghijabcdefghijabcdefghij", "quan_nguyen.99", "Quan2005", "q12"})
    void usernameWithinLengthAndCharacterSetPasses(String username) {
        assertRegisterPasses(username, PASSWORD, EMAIL, PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijabcdefghijabcdefghijk", "quan nguyen", "quan-nguyen", "quan@x", "quân"})
    void usernameOutsideLengthOrCharacterSetIsRejected(String username) {
        assertRegisterRejected(register(username, PASSWORD, EMAIL, PHONE), "username", USERNAME_FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "2005quan", "_quan", ".quan", "..."})
    void usernameNotStartingWithALetterIsRejected(String username) {
        assertRegisterRejected(register(username, PASSWORD, EMAIL, PHONE), "username", USERNAME_FORMAT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "        "})
    void missingPasswordIsRequired(String password) {
        assertRegisterRejected(register(USERNAME, password, EMAIL, PHONE), "password", "Password is required");
    }

    @Test
    void passwordOfEightAndSixtyFourCharactersPasses() {
        assertRegisterPasses(USERNAME, "Abcdef1!", EMAIL, PHONE);
        assertRegisterPasses(USERNAME, "Abcdef1!" + "a".repeat(56), EMAIL, PHONE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 65, 100})
    void passwordOutsideEightToSixtyFourCharactersIsRejected(int length) {
        String password = ("Ab1!" + "a".repeat(length)).substring(0, length);

        assertRegisterRejected(register(USERNAME, password, EMAIL, PHONE), "password", PASSWORD_LENGTH);
    }

    @Test
    void passwordIsNotTrimmedBeforeTheLengthCheck() {
        // 8 characters with the spaces, 6 without
        assertRegisterPasses(USERNAME, " Abc@12 ", EMAIL, PHONE);
    }

    @Test
    void passwordOfSeventyTwoUtf8BytesPasses() {
        // "é" takes 2 bytes: 5 + 33 * 2 + 1 = 72 bytes in 39 characters
        String password = "Abc1!" + "é".repeat(33) + "a";

        assertRegisterPasses(USERNAME, password, EMAIL, PHONE);
    }

    @Test
    void passwordOverSeventyTwoUtf8BytesIsRejectedEvenWithinSixtyFourCharacters() {
        // 5 + 34 * 2 = 73 bytes in 39 characters, which BCrypt would silently truncate
        String password = "Abc1!" + "é".repeat(34);

        assertRegisterRejected(register(USERNAME, password, EMAIL, PHONE), "password", PASSWORD_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdef1!", "ABCDEF1!", "Abcdefg!", "Abcdefg1", "Abcdef 1"})
    void passwordWithoutUpperLowerDigitOrSpecialIsRejected(String password) {
        assertRegisterRejected(register(USERNAME, password, EMAIL, PHONE), "password", PASSWORD_COMPOSITION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcdef1@", "Abcdef1#", "Abcdef1$", "Abcdef1%", "Abcdef1_", "Abcdef1-"})
    void anyNonAlphanumericNonWhitespaceCharacterCountsAsSpecial(String password) {
        assertRegisterPasses(USERNAME, password, EMAIL, PHONE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingEmailIsRequired(String email) {
        assertRegisterRejected(register(USERNAME, PASSWORD, email, PHONE), "email", "Email is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b.co", "quan.nguyen@gmail.com", "quan+food_99%x@mail-server.edu.vn",
            "quan@my--domain.com", "quan-@gmail.com"})
    void wellFormedEmailPasses(String email) {
        assertRegisterPasses(USERNAME, PASSWORD, email, PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quan", "quan@gmail", "quan@@gmail.com", "quan nguyen@gmail.com", "@gmail.com",
            "quan@gmail.c", "quan@.com."})
    void malformedEmailIsRejected(String email) {
        assertRegisterRejected(register(USERNAME, PASSWORD, email, PHONE), "email", EMAIL_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {".quan@gmail.com", "quan.@gmail.com", "quan..nguyen@gmail.com", "quan@gmail..com",
            "quan@-gmail.com", "quan@gmail-.com", "quan@gmail.com."})
    void emailWithMisplacedDotOrHyphenIsRejected(String email) {
        assertRegisterRejected(register(USERNAME, PASSWORD, email, PHONE), "email", EMAIL_INVALID);
    }

    @Test
    void emailOf254CharactersPassesAnd255IsRejected() {
        String email254 = "a".repeat(64) + "@" + "b".repeat(185) + ".com";
        String email255 = "a".repeat(64) + "@" + "b".repeat(186) + ".com";
        assertThat(email254).hasSize(254);
        assertThat(email255).hasSize(255);

        assertRegisterPasses(USERNAME, PASSWORD, email254, PHONE);
        assertRegisterRejected(register(USERNAME, PASSWORD, email255, PHONE), "email", EMAIL_INVALID);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingPhoneNumberIsRequired(String phoneNumber) {
        assertRegisterRejected(register(USERNAME, PASSWORD, EMAIL, phoneNumber), "phoneNumber",
                "Phone number is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0112345678", "0412345678", "091234567", "09123456789", "+8491234567", "+1 555 123 4567",
            "abc"})
    void phoneNumberThatIsNotAVietnameseMobileIsRejected(String phoneNumber) {
        assertRegisterRejected(register(USERNAME, PASSWORD, EMAIL, phoneNumber), "phoneNumber", PHONE_INVALID);
    }

    @Test
    void everyInvalidRegisterFieldIsReportedTogether() {
        BusinessException e = registerError(register("ab", "short", "quan", "123"));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_REGISTER_INFO");
        assertThat(e.getErrors()).extracting(FieldErrorDetail::field, FieldErrorDetail::message).containsExactly(
                tuple("username", USERNAME_FORMAT),
                tuple("password", PASSWORD_LENGTH),
                tuple("email", EMAIL_INVALID),
                tuple("phoneNumber", PHONE_INVALID));
    }

    @Test
    void emptyRegisterObjectReportsEveryFieldAsRequired() {
        BusinessException e = registerError(request("/auth/register", "{}"));

        assertThat(e.getCode()).isEqualTo("INVALID_REGISTER_INFO");
        assertThat(e.getErrors()).extracting(FieldErrorDetail::field)
                .containsExactly("username", "password", "email", "phoneNumber");
    }

    @Test
    void validLoginBodyPasses() {
        assertLoginPasses("quan", PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {" Quan@Gmail.com ", "0912.345.678", "not a valid anything !!", "x"})
    void loginIdentifierFormatIsNotChecked(String identifier) {
        assertLoginPasses(identifier, PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "abc", "password", "12345678"})
    void loginDoesNotApplyTheRegistrationPasswordRules(String password) {
        assertLoginPasses("quan", password);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingLoginIdentifierIsRequired(String identifier) {
        assertLoginRejected(login(identifier, PASSWORD), "identifier", "Username, email or phone number is required");
    }

    @Test
    void loginIdentifierOf254CharactersPassesAnd255IsRejected() {
        assertLoginPasses("a".repeat(254), PASSWORD);
        assertLoginRejected(login("a".repeat(255), PASSWORD), "identifier",
                "Username, email or phone number must be at most 254 characters");
    }

    @Test
    void loginIdentifierLengthIgnoresSurroundingSpaces() {
        assertLoginPasses("   " + "a".repeat(254) + "   ", PASSWORD);
        assertLoginPasses(" " + "a".repeat(254) + " ", PASSWORD);
    }

    @Test
    void loginIdentifierOfUnicodeSpacesOnlyIsRequired() {
        assertLoginRejected(login("   ", PASSWORD), "identifier",
                "Username, email or phone number is required");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingLoginPasswordIsRequired(String password) {
        assertLoginRejected(login("quan", password), "password", "Password is required");
    }

    @Test
    void loginPasswordOfSeventyTwoBytesPassesAndSeventyThreeIsRejected() {
        assertLoginPasses("quan", "a".repeat(72));
        assertLoginPasses("quan", "é".repeat(36));
        assertLoginRejected(login("quan", "a".repeat(73)), "password", "Password is too long");
        assertLoginRejected(login("quan", "é".repeat(36) + "a"), "password", "Password is too long");
    }

    @Test
    void everyInvalidLoginFieldIsReportedTogether() {
        BusinessException e = loginError(login(" ", null));

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_LOGIN_INFO");
        assertThat(e.getErrors()).extracting(FieldErrorDetail::field).containsExactly("identifier", "password");
    }

    private void assertRegisterPasses(String username, String password, String email, String phoneNumber) {
        assertThatCode(() -> validator.validateRegister(register(username, password, email, phoneNumber)))
                .doesNotThrowAnyException();
    }

    private void assertRegisterRejected(ApiRequest request, String field, String message) {
        BusinessException e = registerError(request);

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_REGISTER_INFO");
        assertThat(e.getErrors()).containsExactly(new FieldErrorDetail(field, message));
    }

    private BusinessException registerError(ApiRequest request) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> validator.validateRegister(request));
        assertThat(e).as("BusinessException from validateRegister").isNotNull();
        return e;
    }

    private void assertLoginPasses(String identifier, String password) {
        assertThatCode(() -> validator.validateLogin(login(identifier, password))).doesNotThrowAnyException();
    }

    private void assertLoginRejected(ApiRequest request, String field, String message) {
        BusinessException e = loginError(request);

        assertThat(e.getStatus()).isEqualTo(ApiStatus.BAD_REQUEST);
        assertThat(e.getCode()).isEqualTo("INVALID_LOGIN_INFO");
        assertThat(e.getErrors()).containsExactly(new FieldErrorDetail(field, message));
    }

    private BusinessException loginError(ApiRequest request) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> validator.validateLogin(request));
        assertThat(e).as("BusinessException from validateLogin").isNotNull();
        return e;
    }

    private static ApiRequest register(String username, String password, String email, String phoneNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("email", email);
        body.put("phoneNumber", phoneNumber);
        return request("/auth/register", JSON.writeValueAsString(body));
    }

    private static ApiRequest login(String identifier, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identifier", identifier);
        body.put("password", password);
        return request("/auth/login", JSON.writeValueAsString(body));
    }

    private static ApiRequest request(String path, String json) {
        return new ApiRequest(new MockHttpServletRequest("POST", path), Map.of(), json);
    }
}
