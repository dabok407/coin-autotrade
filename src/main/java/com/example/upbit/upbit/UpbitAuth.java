package com.example.upbit.upbit;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.*;

public class UpbitAuth {

    private static final Logger log = LoggerFactory.getLogger(UpbitAuth.class);
    /**
     * 업비트 인증용 JWT 생성.
     *
     * ※ 업비트 v1.6.1(2025-10-27)부터 HS512(HMAC-SHA512) 서명을 권장/필수합니다.
     *   기존 HS256은 일부 구형 엔드포인트에서만 허용됩니다.
     *   https://docs.upbit.com/kr/reference/auth
     */
    public static String createJwt(String accessKey, String secretKey, Map<String, String> params) {
        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("access_key", accessKey);
        claims.put("nonce", UUID.randomUUID().toString());

        if (params != null && !params.isEmpty()) {
            String queryString = buildQueryString(params);
            byte[] hash = DigestUtils.sha512(queryString.getBytes(StandardCharsets.UTF_8));
            String hashHex = Hex.encodeHexString(hash);
            claims.put("query_hash", hashHex);
            claims.put("query_hash_alg", "SHA512");
            log.info("[UPBIT-AUTH] queryString='{}' → hash={}", queryString, hashHex.substring(0, 16) + "...");
        }

        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        // jjwt 0.11.x는 HS512에 최소 512비트(64바이트) 키를 요구합니다.
        // 업비트 secret key는 40바이트(320비트)인데, HMAC 스펙(RFC 2104)에 따라
        // 블록 크기보다 짧은 키는 제로패딩됩니다. 따라서 결과는 동일합니다.
        if (keyBytes.length < 64) {
            byte[] padded = new byte[64];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        Key signingKey = new SecretKeySpec(keyBytes, "HmacSHA512");

        return Jwts.builder()
                .setClaims(claims)
                .signWith(signingKey, SignatureAlgorithm.HS512)
                .compact();
    }

    private static String buildQueryString(Map<String, String> params) {
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = params.get(k);
            if (i > 0) sb.append("&");
            sb.append(k).append("=").append(v);
        }
        return sb.toString();
    }
}
