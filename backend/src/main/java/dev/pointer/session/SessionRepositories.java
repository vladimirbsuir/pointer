package dev.pointer.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    Optional<AgentSession> findByIdAndUserId(UUID id, UUID userId);

    List<AgentSession> findByUserIdAndProjectIdOrderByUpdatedAtDesc(UUID userId, UUID projectId);

    List<AgentSession> findByStatusIn(List<String> statuses);

    List<AgentSession> findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID userId, Limit limit);
}

interface TurnRepository extends JpaRepository<Turn, UUID> {

    Optional<Turn> findBySessionIdAndIdempotencyKey(UUID sessionId, String idempotencyKey);

    @Query("select coalesce(max(t.number), 0) from Turn t where t.sessionId = :sessionId")
    int maxNumber(UUID sessionId);

    List<Turn> findByStatus(String status);

    List<Turn> findBySessionIdAndNumberGreaterThanEqualAndRewoundAtIsNullOrderByNumber(UUID sessionId, int number);
}

interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findBySessionIdOrderByPosition(UUID sessionId);

    @Query("""
            select i from Item i
            where i.sessionId = :sessionId
              and (i.turnId is null or i.turnId not in (
                  select t.id from Turn t where t.sessionId = :sessionId and t.rewoundAt is not null))
            order by i.position""")
    List<Item> findVisibleBySessionId(UUID sessionId);

    List<Item> findBySessionIdAndTurnIdIsNullOrderByPosition(UUID sessionId);

    List<Item> findBySessionIdAndType(UUID sessionId, String type);

    List<Item> findByStatus(String status);

    List<Item> findBySessionIdAndTurnIdAndStatus(UUID sessionId, UUID turnId, String status);
}

interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    Optional<Attachment> findByIdAndSessionId(UUID id, UUID sessionId);
}
