package com.example.upbit.db;

import javax.persistence.*;

/**
 * Stores encrypted Upbit API keys.
 *
 * NOTE (H2): Using @Lob to map to BLOB so Hibernate schema validation
 * matches the Flyway migration.
 */
@Entity
@Table(name = "api_key_store")
public class ApiKeyEntity {

    @Id
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "access_key_enc", nullable = false)
    private byte[] accessKeyEnc;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "secret_key_enc", nullable = false)
    private byte[] secretKeyEnc;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public byte[] getAccessKeyEnc() {
        return accessKeyEnc;
    }

    public void setAccessKeyEnc(byte[] accessKeyEnc) {
        this.accessKeyEnc = accessKeyEnc;
    }

    public byte[] getSecretKeyEnc() {
        return secretKeyEnc;
    }

    public void setSecretKeyEnc(byte[] secretKeyEnc) {
        this.secretKeyEnc = secretKeyEnc;
    }
}
