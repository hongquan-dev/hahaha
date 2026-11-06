package com.fooddelivery.auth.config.security;

import com.fooddelivery.auth.config.properties.AuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA key pair that signs the access tokens (RS256), built at startup from {@code auth.jwt.private-key},
 * {@code auth.jwt.public-key} and {@code auth.jwt.key-id}.
 *
 * <p>Throws {@code IllegalStateException} at startup, so the application refuses to start, if a key cannot be parsed,
 * the two keys are not a pair, or the key is shorter than 2048 bits; the message names the faulty property.
 */
@Configuration
public class JwtKeyConfig {

    // NIST minimum for RSA; shorter keys can be factored and the tokens forged
    private static final int MIN_KEY_BITS = 2048;

    /**
     * Key pair with its {@code kid}, shared by the token signer and verifier and by the JWKS endpoint (which only
     * publishes {@code toPublicJWK()}).
     *
     * @param properties bound {@code auth.*} properties
     * @return the key pair, marked for RS256 signatures
     */
    @Bean
    public RSAKey jwtSigningKey(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.jwt();
        RSAPrivateKey privateKey = parsePrivateKey(jwt.privateKey());
        RSAPublicKey publicKey = parsePublicKey(jwt.publicKey());

        if (publicKey.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalStateException("auth.jwt.public-key is " + publicKey.getModulus().bitLength()
                    + " bits, at least " + MIN_KEY_BITS + " are required");
        }
        // Same modulus (and public exponent, when the private key carries it) means the keys belong together;
        // otherwise every token would be signed with a key that its own public key rejects
        boolean samePair = publicKey.getModulus().equals(privateKey.getModulus())
                && (!(privateKey instanceof RSAPrivateCrtKey crtKey)
                    || publicKey.getPublicExponent().equals(crtKey.getPublicExponent()));
        if (!samePair) {
            throw new IllegalStateException("auth.jwt.private-key and auth.jwt.public-key are not a key pair");
        }

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(jwt.keyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("auth.jwt.private-key is not a PKCS#8 RSA private key: " + e.getMessage(),
                    e);
        }
    }

    private static RSAPublicKey parsePublicKey(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("auth.jwt.public-key is not an X.509 RSA public key: " + e.getMessage(), e);
        }
    }

    // DER bytes of a PEM key, with or without its BEGIN/END lines, e.g. "-----BEGIN PUBLIC KEY-----\nMIIB..." or
    // "MIIB...". Whitespace and line breaks anywhere are ignored
    private static byte[] decodePem(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
