package com.pulseops.credential.service;

import java.time.Instant;
import java.util.UUID;

import com.pulseops.credential.domain.CredentialStatus;
import com.pulseops.credential.domain.IntegrationCredential;

/** Credential metadata. Never contains the key or its hash. */
public record CredentialView(UUID id, UUID projectId, UUID serviceId, String name, String keyPrefix,
                             CredentialStatus status, Instant expiresAt, Instant revokedAt, Instant lastUsedAt,
                             UUID rotatedFromId, Instant createdAt) {

    public static CredentialView from(IntegrationCredential c, Instant now) {
        return new CredentialView(c.getId(), c.getProjectId(), c.getServiceId(), c.getName(), c.getKeyPrefix(),
                c.effectiveStatus(now), c.getExpiresAt(), c.getRevokedAt(), c.getLastUsedAt(), c.getRotatedFromId(),
                c.getCreatedAt());
    }

    /** Returned only by create/rotate: the plaintext key is in this response and nowhere else, ever. */
    public record Issued(CredentialView credential, String key, String notice) {

        public static Issued of(CredentialView credential, String key) {
            return new Issued(credential, key,
                    "Copy this key now. It is shown only once and cannot be retrieved later.");
        }
    }
}
