package com.pulseops.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.pulseops.auth.domain.ActionPurpose;
import com.pulseops.auth.domain.UserActionToken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserActionTokenRepository extends JpaRepository<UserActionToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UserActionToken t where t.tokenHash = :hash")
    Optional<UserActionToken> findByTokenHashForUpdate(@Param("hash") String tokenHash);

    /** Invalidates outstanding tokens so only the most recently issued link works. */
    @Modifying
    @Query("""
            update UserActionToken t set t.consumedAt = :now
            where t.userId = :userId and t.purpose = :purpose and t.consumedAt is null
            """)
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("purpose") ActionPurpose purpose,
                              @Param("now") Instant now);

    @Modifying
    @Query("delete from UserActionToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
