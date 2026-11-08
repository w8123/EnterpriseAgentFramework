package com.enterprise.ai.control.identity.sso;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts business-system login client secrets at rest. The key is REACHAI_BUSINESS_SSO_KEY_BASE64 when set,
 * otherwise derived from the internal service secret every ReachAI install already has. Rotating that secret
 * means re-entering the client secret.
 */
@Component
public class BusinessSsoSecretCipher {

    public static final String PREFIX = "aesgcm:v1:";
    private static final int NONCE_BYTES = 12;
    private static final String AAD = "reachai-business-sso-client-secret";
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public BusinessSsoSecretCipher(
            @Value("${REACHAI_BUSINESS_SSO_KEY_BASE64:${reachai.business-sso.key-base64:}}") String keyBase64,
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String serviceSecret) {
        this.key = resolveKey(keyBase64, serviceSecret);
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        if (key == null) throw new IllegalStateException("没有配置加密密钥，无法保存登录密钥");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(sealed);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("登录密钥加密失败");
        }
    }

    public String decrypt(String stored) {
        if (key == null || stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("登录密钥不可用，请到认证源重新填写");
        }
        String[] parts = stored.substring(PREFIX.length()).split(":", 2);
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | ArrayIndexOutOfBoundsException failure) {
            throw new IllegalStateException("登录密钥不可用，请到认证源重新填写");
        }
    }

    static byte[] resolveKey(String keyBase64, String serviceSecret) {
        if (StringUtils.hasText(keyBase64)) {
            byte[] decoded = Base64.getDecoder().decode(keyBase64.trim());
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) return decoded;
            throw new IllegalStateException("REACHAI_BUSINESS_SSO_KEY_BASE64 must decode to 16, 24 or 32 bytes");
        }
        if (!StringUtils.hasText(serviceSecret)) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serviceSecret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("reachai-business-sso-key-v1".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            return null;
        }
    }
}
