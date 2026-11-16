package com.fooddelivery.auth.config.security;

import com.fooddelivery.auth.config.properties.AuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the startup checks of {@link JwtKeyConfig#jwtSigningKey}, without Spring: RSA key pairs are generated
 * in the test and passed as PEM text through {@link AuthProperties}, as {@code application.properties} would.
 */
class JwtKeyConfigTest {

    private static final String KEY_ID = "fd-auth-test-1";

    // Generated once: RSA key generation takes a noticeable fraction of a second
    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static KeyPair shortKeyPair;

    private final JwtKeyConfig config = new JwtKeyConfig();

    @BeforeAll
    static void generateKeys() throws Exception {
        keyPair = generate(2048);
        otherKeyPair = generate(2048);
        shortKeyPair = generate(1024);
    }

    @Test
    void validPairGivesAnRs256SigningKeyWithItsKeyId() throws Exception {
        RSAKey key = config.jwtSigningKey(properties(privatePem(keyPair), publicPem(keyPair)));

        assertThat(key.getKeyID()).isEqualTo(KEY_ID);
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey()).isEqualTo(keyPair.getPublic());
    }

    @Test
    void tokenSignedWithTheKeyVerifiesWithItsPublicKey() throws Exception {
        RSAKey key = config.jwtSigningKey(properties(privatePem(keyPair), publicPem(keyPair)));
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().subject("1").build());

        jwt.sign(new RSASSASigner(key));

        assertThat(jwt.verify(new RSASSAVerifier(key.toPublicJWK()))).isTrue();
    }

    @Test
    void bareBase64BodyWithoutPemLinesIsAccepted() throws Exception {
        String privateBody = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicBody = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        RSAKey key = config.jwtSigningKey(properties(privateBody, publicBody));

        assertThat(key.toRSAPublicKey()).isEqualTo(keyPair.getPublic());
    }

    @Test
    void keysOfTwoDifferentPairsAreRejected() {
        assertThatThrownBy(() -> config.jwtSigningKey(properties(privatePem(keyPair), publicPem(otherKeyPair))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auth.jwt.private-key and auth.jwt.public-key are not a key pair");
    }

    @Test
    void publicKeyWithTheSameModulusButAnotherExponentIsRejected() throws Exception {
        RSAPublicKey original = (RSAPublicKey) keyPair.getPublic();
        RSAPublicKey otherExponent = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(original.getModulus(), BigInteger.valueOf(3)));

        assertThatThrownBy(() -> config.jwtSigningKey(properties(privatePem(keyPair), pem("PUBLIC KEY",
                otherExponent.getEncoded()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auth.jwt.private-key and auth.jwt.public-key are not a key pair");
    }

    @Test
    void keyShorterThan2048BitsIsRejected() {
        assertThatThrownBy(() -> config.jwtSigningKey(properties(privatePem(shortKeyPair), publicPem(shortKeyPair))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auth.jwt.public-key is 1024 bits")
                .hasMessageContaining("2048");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 !!", "aGVsbG8gd29ybGQ="})
    void malformedPrivateKeyIsRejectedNamingItsProperty(String privateKey) {
        assertThatThrownBy(() -> config.jwtSigningKey(properties(privateKey, publicPem(keyPair))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auth.jwt.private-key is not a PKCS#8 RSA private key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 !!", "aGVsbG8gd29ybGQ="})
    void malformedPublicKeyIsRejectedNamingItsProperty(String publicKey) {
        assertThatThrownBy(() -> config.jwtSigningKey(properties(privatePem(keyPair), publicKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auth.jwt.public-key is not an X.509 RSA public key");
    }

    @Test
    void swappedKeysAreRejectedAtThePrivateKey() {
        assertThatThrownBy(() -> config.jwtSigningKey(properties(publicPem(keyPair), privatePem(keyPair))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auth.jwt.private-key is not a PKCS#8 RSA private key");
    }

    @Test
    void privateKeyGivenAsPublicKeyIsRejected() {
        assertThatThrownBy(() -> config.jwtSigningKey(properties(privatePem(keyPair), privatePem(keyPair))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auth.jwt.public-key is not an X.509 RSA public key");
    }

    @Test
    void nonRsaKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair ecPair = generator.generateKeyPair();

        assertThatThrownBy(() -> config.jwtSigningKey(properties(pem("PRIVATE KEY", ecPair.getPrivate().getEncoded()),
                publicPem(keyPair))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auth.jwt.private-key");
    }

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String privatePem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    private static String publicPem(KeyPair pair) {
        return pem("PUBLIC KEY", pair.getPublic().getEncoded());
    }

    // PEM with BEGIN/END lines and the body wrapped at 64 characters, as written by openssl
    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static AuthProperties properties(String privateKey, String publicKey) {
        return new AuthProperties(
                new AuthProperties.Jwt(privateKey, publicKey, KEY_ID, "fd-auth-service", Duration.ofMinutes(15)),
                new AuthProperties.Refresh(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10)),
                new AuthProperties.Cookie(true),
                List.of("http://localhost:5173"));
    }
}
