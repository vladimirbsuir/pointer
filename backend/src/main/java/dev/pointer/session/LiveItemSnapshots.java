package dev.pointer.session;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LiveItemSnapshots {

    List<Map<String, Object>> snapshots(UUID sessionId);
}
