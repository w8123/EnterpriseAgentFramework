package com.enterprise.ai.control.a2a.application.port;

/** Key-separated envelope encryption boundary for outbound A2A credential material. */
public interface A2aCredentialCipher {

    EncryptedSecret encrypt(byte[] plaintext, String aad);

    byte[] decrypt(EncryptedSecret secret, String aad);

    record EncryptedSecret(String keyId, String nonce, String ciphertext) {
    }
}
