package com.example.upbit.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encrypt/decrypt helper.
 * Master key는 환경변수(또는 yml)로 주입: UPBIT_KEYSTORE_MASTER (Base64 32 bytes 권장)
 *
 * 저장 포맷: [12 bytes IV] + [ciphertext+tag]
 */
@Component
public class AesGcmCrypto {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom rnd = new SecureRandom();
    private final SecretKey key;

    public AesGcmCrypto(@Value("${upbit.keyStore.masterKey:}") String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty()) {
            this.key = null;
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalArgumentException("upbit.keyStore.masterKey must be 32 bytes (base64).");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(String plain) {
        if (!isConfigured()) throw new IllegalStateException("KeyStore masterKey not configured.");
        if (plain == null) plain = "";
        try {
            byte[] iv = new byte[IV_LEN];
            rnd.nextBytes(iv);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String decrypt(byte[] enc) {
        if (!isConfigured()) throw new IllegalStateException("KeyStore masterKey not configured.");
        if (enc == null || enc.length <= IV_LEN) return "";
        try {
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[enc.length - IV_LEN];
            System.arraycopy(enc, 0, iv, 0, IV_LEN);
            System.arraycopy(enc, IV_LEN, ct, 0, ct.length);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
