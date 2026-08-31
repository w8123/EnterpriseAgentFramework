package com.enterprise.ai.control.a2a.application.port;

/** Envelope encryption boundary for retained A2A Message and Artifact content. */
public interface A2aContentCipher {

    EncryptedContent encrypt(byte[] plaintext, String aad);

    byte[] decrypt(EncryptedContent content, String aad);

    record EncryptedContent(
            String keyId,
            String nonce,
            String ciphertext) {
    }
}
