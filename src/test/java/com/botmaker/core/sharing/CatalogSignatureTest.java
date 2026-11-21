// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code catalog.signed.json} verifies only by a trusted key, over the exact payload (2026-11-21). */
class CatalogSignatureTest {

    /** The public half of the throwaway key {@code signed-by-openssl.json} was signed with by sign-catalog.sh. */
    private static final String OPENSSL_KEY = "MCowBQYDK2VwAyEAxDYd4HBxE4UGFJNAuLyygHZmStuhZomDsjlV/eGwb/s=";

    static KeyPair newKey() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** {@code payload} signed by {@code pair}, as sign-catalog.sh writes it. */
    static String signed(String payload, KeyPair pair) throws GeneralSecurityException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(bytes);
        Base64.Encoder base64 = Base64.getEncoder();
        return """
                {"payload": "%s", "key": "%s", "signature": "%s"}
                """.formatted(base64.encodeToString(bytes), CatalogSignature.id(pair.getPublic()),
                base64.encodeToString(signer.sign()));
    }

    @Test
    void verifiesGoodSignature() throws Exception {
        KeyPair pair = newKey();
        assertEquals(Optional.of("{\"bots\": []}"),
                CatalogSignature.verify(signed("{\"bots\": []}", pair), List.of(pair.getPublic())));
    }

    /** The signing script's own output: openssl's Ed25519 and the JDK's agree, and so do their key ids. */
    @Test
    void verifiesWhatSignCatalogWrote() throws Exception {
        String file;
        try (InputStream in = getClass().getResourceAsStream("signed-by-openssl.json")) {
            file = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Optional<String> payload = CatalogSignature.verify(file, List.of(CatalogSignature.key(OPENSSL_KEY)));
        assertTrue(payload.orElse("").contains("a/openssl"), payload::toString);
    }

    @Test
    void refusesOtherKey() throws Exception {
        KeyPair signer = newKey();
        PublicKey other = newKey().getPublic();
        assertEquals(Optional.empty(), CatalogSignature.verify(signed("{}", signer), List.of(other)));
    }

    /** A key trusted, its id claimed, but another key signed: still refused. */
    @Test
    void refusesAnotherKeysSignatureUnderATrustedId() throws Exception {
        KeyPair trusted = newKey();
        String forged = signed("{}", newKey()).replaceFirst("\"key\": \"[0-9a-f]+\"",
                "\"key\": \"" + CatalogSignature.id(trusted.getPublic()) + "\"");
        assertEquals(Optional.empty(), CatalogSignature.verify(forged, List.of(trusted.getPublic())));
    }

    @Test
    void refusesTamperedPayload() throws Exception {
        KeyPair pair = newKey();
        String good = signed("{\"bots\": []}", pair);
        String other = Base64.getEncoder().encodeToString("{\"bots\": [1]}".getBytes(StandardCharsets.UTF_8));
        String tampered = good.replaceFirst("\"payload\": \"[^\"]+\"", "\"payload\": \"" + other + "\"");
        assertEquals(Optional.empty(), CatalogSignature.verify(tampered, List.of(pair.getPublic())));
    }

    @Test
    void refusesWhatIsNoSignedFile() throws Exception {
        List<PublicKey> keys = List.of(newKey().getPublic());
        assertEquals(Optional.empty(), CatalogSignature.verify(null, keys));
        assertEquals(Optional.empty(), CatalogSignature.verify("{\"bots\": []}", keys));
        assertEquals(Optional.empty(), CatalogSignature.verify("not json", keys));
        assertEquals(Optional.empty(), CatalogSignature.verify("{\"payload\": \"%%%\", \"signature\": \"x\"}", keys));
    }

    /** Studio ships with the catalog's key, so the live catalog verifies. */
    @Test
    void theCatalogKeyShips() {
        assertFalse(CatalogSignature.shipped().isEmpty(), CatalogSignature.KEYS_RESOURCE + " holds no key");
    }
}
