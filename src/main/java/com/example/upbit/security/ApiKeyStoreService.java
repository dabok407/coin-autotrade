package com.example.upbit.security;

import com.example.upbit.db.ApiKeyEntity;
import com.example.upbit.db.ApiKeyRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ApiKeyStoreService {

    private static final String PROVIDER = "UPBIT";

    private final ApiKeyRepository repo;
    private final AesGcmCrypto crypto;

    public ApiKeyStoreService(ApiKeyRepository repo, AesGcmCrypto crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    public boolean isConfigured() {
        // DB 키가 있거나, crypto 미설정이면 false(단, 환경변수 키는 UpbitPrivateClient에서 fallback)
        Optional<ApiKeyEntity> e = repo.findByProvider(PROVIDER);
        return e.isPresent() && crypto.isConfigured();
    }

    public void saveUpbitKeys(String accessKey, String secretKey) {
        if (!crypto.isConfigured()) {
            throw new IllegalStateException("UPBIT_KEYSTORE_MASTER (base64) not configured on server.");
        }
        ApiKeyEntity e = repo.findByProvider(PROVIDER).orElseGet(ApiKeyEntity::new);
        e.setProvider(PROVIDER);
        e.setAccessKeyEnc(crypto.encrypt(accessKey));
        e.setSecretKeyEnc(crypto.encrypt(secretKey));
        repo.save(e);
    }

    public Keys loadUpbitKeys() {
        if (!crypto.isConfigured()) return null;
        Optional<ApiKeyEntity> e = repo.findByProvider(PROVIDER);
        if (!e.isPresent()) return null;
        String ak = crypto.decrypt(e.get().getAccessKeyEnc());
        String sk = crypto.decrypt(e.get().getSecretKeyEnc());
        if (ak == null || ak.isEmpty() || sk == null || sk.isEmpty()) return null;
        return new Keys(ak, sk);
    }

    public static class Keys {
        public final String accessKey;
        public final String secretKey;
        public Keys(String accessKey, String secretKey) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
        }
    }
}
