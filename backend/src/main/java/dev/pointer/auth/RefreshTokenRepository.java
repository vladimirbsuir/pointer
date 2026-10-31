package dev.pointer.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    void revokeFamily(UUID familyId, Instant now);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.userId = :userId and t.deviceId <> :deviceId and t.revokedAt is null
            """)
    void revokeOtherDevices(UUID userId, UUID deviceId, Instant now);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.deviceId = :deviceId and t.userId <> :userId and t.revokedAt is null
            """)
    void revokeOtherUsersOnDevice(UUID deviceId, UUID userId, Instant now);
}
