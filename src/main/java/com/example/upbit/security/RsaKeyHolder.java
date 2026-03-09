package com.example.upbit.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/**
 * 서버 기동 시 RSA 2048-bit 키쌍을 메모리에 생성.
 * - 공개키: 클라이언트에 전달 → 비밀번호 암호화에 사용
 * - 개인키: 서버에서만 보유 → 복호화에 사용
 *
 * 키는 서버 재시작마다 새로 생성되므로 탈취 리스크가 낮음.
 */
@Component
public class RsaKeyHolder {
    private static final Logger log = LoggerFactory.getLogger(RsaKeyHolder.class);

    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            this.keyPair = gen.generateKeyPair();
            log.info("RSA 2048-bit 키쌍 생성 완료");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 키 생성 실패", e);
        }
    }

    /** 공개키를 Base64 PEM-body 형태로 반환 */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** RSA 복호화 (JSEncrypt 클라이언트는 PKCS1v1.5 패딩 사용) */
    public String decrypt(String base64Cipher) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            byte[] decoded = Base64.getDecoder().decode(base64Cipher);
            byte[] plain = cipher.doFinal(decoded);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("RSA 복호화 실패: {}", e.getMessage());
            return null;
        }
    }
}
