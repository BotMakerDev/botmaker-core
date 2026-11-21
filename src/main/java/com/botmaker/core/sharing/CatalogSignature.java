// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Checks {@code catalog.signed.json} (2026-11-21): {@code {"payload", "key", "signature"}}, the catalog's bytes in
 * base64, the id of the key that signed them and their Ed25519 signature, written by the catalog's
 * {@code tools/sign-catalog.sh} in {@code index.yml}. The public keys trusted are {@link #KEYS_RESOURCE}, one
 * base64 DER key a line, so a new key ships beside the old one before the catalog signs with it.
 *
 * <p>Ed25519 rather than sigstore, the user's call: sigstore-java brought 33 MB of jars, and the JDK verifies
 * Ed25519 itself.
 */
public final class CatalogSignature {

    /** The trusted public keys, beside this class. */
    static final String KEYS_RESOURCE = "catalog-keys.txt";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<PublicKey> KEYS = shipped();

    private CatalogSignature() {
    }

    /** The catalog {@code signedJson} carries when a shipped key signed it; empty when none did, or it is no signed file. */
    public static Optional<String> verify(String signedJson) {
        return verify(signedJson, KEYS);
    }

    static Optional<String> verify(String signedJson, List<PublicKey> keys) {
        if (signedJson == null || signedJson.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(signedJson);
            String keyId = root.path("key").asText("");
            byte[] payload = Base64.getDecoder().decode(root.path("payload").asText(""));
            byte[] signature = Base64.getDecoder().decode(root.path("signature").asText(""));
            for (PublicKey key : keys) {
                if (!id(key).equals(keyId)) continue;
                Signature check = Signature.getInstance("Ed25519");
                check.initVerify(key);
                check.update(payload);
                if (check.verify(signature)) return Optional.of(new String(payload, StandardCharsets.UTF_8));
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** A key's id: the first 16 hex digits of the SHA-256 of its DER form, as {@code sign-catalog.sh} names it. */
    static String id(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The Ed25519 public key {@code base64Der} holds. */
    static PublicKey key(String base64Der) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Der.strip())));
    }

    /** The keys of {@link #KEYS_RESOURCE}; a line that is blank, a {@code #} comment or no key is skipped. */
    static List<PublicKey> shipped() {
        List<PublicKey> keys = new ArrayList<>();
        try (InputStream in = CatalogSignature.class.getResourceAsStream(KEYS_RESOURCE)) {
            if (in == null) return List.of();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.strip().startsWith("#")) continue;
                try {
                    keys.add(key(line));
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    System.err.println("Skipped a catalog key that is no Ed25519 key: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(keys);
    }
}
