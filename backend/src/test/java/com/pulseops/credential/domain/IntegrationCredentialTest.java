package com.pulseops.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IntegrationCredentialTest {

    private final Instant now = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    void keysHaveRecognizableFormatAndOnlyTheHashIsStored() {
        String key = IntegrationKeys.generate();
        assertThat(key).startsWith("po_").hasSize(46);
        assertThat(IntegrationKeys.looksValid(key)).isTrue();

        IntegrationCredential credential = credential(key, null);

        assertThat(credential.getKeyPrefix()).isEqualTo(key.substring(0, 12));
        assertThat(credential.getSecretHash()).isEqualTo(IntegrationKeys.hash(key)).doesNotContain(key.substring(3));
    }

    @Test
    void malformedKeysAreRejectedSyntactically() {
        assertThat(IntegrationKeys.looksValid(null)).isFalse();
        assertThat(IntegrationKeys.looksValid("po_short")).isFalse();
        assertThat(IntegrationKeys.looksValid("xx_" + "a".repeat(43))).isFalse();
        assertThat(IntegrationKeys.looksValid("po_" + "a".repeat(42) + "!")).isFalse();
        assertThat(IntegrationKeys.looksValid("po_" + "a".repeat(43) + "\n")).isFalse();
    }

    @Test
    void pastExpiryReportsExpiredEvenWhileStoredActive() {
        IntegrationCredential credential = credential(IntegrationKeys.generate(), now.plusSeconds(60));

        assertThat(credential.effectiveStatus(now)).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(credential.effectiveStatus(now.plusSeconds(60))).isEqualTo(CredentialStatus.EXPIRED);
        assertThat(credential.isUsable(now.plusSeconds(61))).isFalse();
    }

    @Test
    void revocationWinsAndIsIdempotent() {
        IntegrationCredential credential = credential(IntegrationKeys.generate(), null);
        credential.revoke(now);
        credential.revoke(now.plusSeconds(100));

        assertThat(credential.effectiveStatus(now)).isEqualTo(CredentialStatus.REVOKED);
        assertThat(credential.getRevokedAt()).isEqualTo(now);
    }

    @Test
    void graceExpiryOnlyEverShortensLifetime() {
        IntegrationCredential noExpiry = credential(IntegrationKeys.generate(), null);
        noExpiry.expireNoLaterThan(now.plus(Duration.ofHours(24)));
        assertThat(noExpiry.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));

        IntegrationCredential soon = credential(IntegrationKeys.generate(), now.plus(Duration.ofHours(1)));
        soon.expireNoLaterThan(now.plus(Duration.ofHours(24)));
        assertThat(soon.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
    }

    private static IntegrationCredential credential(String key, Instant expiresAt) {
        return new IntegrationCredential(UUID.randomUUID(), UUID.randomUUID(), null, "prod", key, expiresAt, null,
                UUID.randomUUID());
    }
}
