package dev.pointer.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ModelFallbackStore {

    private final JdbcClient jdbc;

    List<UUID> list(UUID modelId) {
        return jdbc.sql("select fallback_model_id from model_fallbacks where model_id = :modelId order by position")
                .param("modelId", modelId)
                .query(UUID.class)
                .list();
    }

    Map<UUID, List<UUID>> listAll(Collection<UUID> modelIds) {
        var result = new HashMap<UUID, List<UUID>>();
        if (modelIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        select model_id, fallback_model_id from model_fallbacks
                        where model_id in (:modelIds)
                        order by model_id, position
                        """)
                .param("modelIds", modelIds)
                .query((rs, row) -> result.computeIfAbsent(
                                rs.getObject("model_id", UUID.class), id -> new ArrayList<>())
                        .add(rs.getObject("fallback_model_id", UUID.class)))
                .list();
        return result;
    }

    void replace(UUID modelId, List<UUID> fallbackModelIds) {
        jdbc.sql("delete from model_fallbacks where model_id = :modelId")
                .param("modelId", modelId)
                .update();
        for (var position = 0; position < fallbackModelIds.size(); position++) {
            jdbc.sql("""
                            insert into model_fallbacks (model_id, position, fallback_model_id)
                            values (:modelId, :position, :fallbackModelId)
                            """)
                    .param("modelId", modelId)
                    .param("position", position)
                    .param("fallbackModelId", fallbackModelIds.get(position))
                    .update();
        }
    }
}
