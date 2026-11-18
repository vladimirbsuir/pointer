package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContextSnapshotTests extends AgentIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONTEXT_BLOCK = "<context>";

    @Autowired
    private JdbcClient jdbc;

    @Test
    void volatileContextIsStoredPerTurnAndTheConversationOnlyGrows() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("First answer.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
            llm.enqueueText("Second answer.");
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
            createMemory(setup);
            llm.enqueueText("Third answer.");
            send(setup, "Third question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 3, TIMEOUT);
        }

        var requests = llm.requests();
        var title = JSON.readTree(requests.get(1));
        var first = JSON.readTree(requests.get(0));
        var second = JSON.readTree(requests.get(2));
        var third = JSON.readTree(requests.get(3));

        var system = first.path("messages").get(0).path("content").asString();
        assertThat(system).doesNotContain("Date:").doesNotContain("Memory:").contains("- Project: demo");
        var opening = first.path("messages").get(1).path("content").asString();
        assertThat(opening)
                .startsWith(CONTEXT_BLOCK)
                .contains("- Date: ")
                .contains("Memory:")
                .endsWith("First question");
        assertThat(title.toString()).doesNotContain(CONTEXT_BLOCK);

        assertThat(isPrefix(first.path("messages"), second.path("messages"))).isTrue();
        assertThat(isPrefix(second.path("messages"), third.path("messages"))).isTrue();
        assertThat(contents(second))
                .filteredOn(text -> text.contains(CONTEXT_BLOCK))
                .hasSize(1);
        var latest = contents(third).getLast();
        assertThat(latest)
                .startsWith(CONTEXT_BLOCK)
                .contains("deploy-on-friday")
                .endsWith("Third question");
        assertThat(third.path("tools")).isEqualTo(second.path("tools"));
        assertThat(toolNames(third)).isNotEmpty().endsWith("explore");

        var snapshots = jdbc.sql("select context_snapshot from turns where session_id = :id order by number")
                .param("id", setup.sessionId())
                .query((rs, row) -> rs.getString(1))
                .list();
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots.get(0)).contains("Memory:");
        assertThat(snapshots.get(1)).isNull();
        assertThat(snapshots.get(2)).contains("deploy-on-friday");
    }

    private void createMemory(Setup setup) {
        client.post()
                .uri("/api/v1/memories")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of(
                        "kind",
                        "project",
                        "projectId",
                        setup.projectId().toString(),
                        "name",
                        "deploy-on-friday",
                        "description",
                        "Deploys happen on Fridays",
                        "content",
                        "Deploy on Fridays only."))
                .exchange()
                .expectStatus()
                .isCreated();
    }

    private static boolean isPrefix(JsonNode shorter, JsonNode longer) {
        if (shorter.size() >= longer.size()) {
            return false;
        }
        for (var index = 0; index < shorter.size(); index++) {
            if (!shorter.get(index).equals(longer.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> contents(JsonNode request) {
        var texts = new ArrayList<String>();
        request.path("messages")
                .forEach(message -> texts.add(message.path("content").asString("")));
        return texts;
    }

    private static List<String> toolNames(JsonNode request) {
        var names = new ArrayList<String>();
        request.path("tools")
                .forEach(tool -> names.add(tool.path("function").path("name").asString()));
        return names;
    }

    private GatewayTestClient viewer(Setup setup) throws Exception {
        var ws = GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"));
        ws.subscribe(setup.sessionId(), 0);
        ws.await(message -> GatewayTestClient.type(message).equals("session.subscribed"), TIMEOUT);
        return ws;
    }

    private static void awaitTitle(GatewayTestClient ws) {
        ws.await(
                message -> eventKind(message).equals("session.updated")
                        && message.path("payload")
                                .path("event")
                                .path("title")
                                .asString()
                                .equals("Title"),
                TIMEOUT);
    }

    private static long countEvents(GatewayTestClient ws, String kind) {
        return ws.events().stream()
                .filter(event -> eventKind(event).equals(kind))
                .count();
    }
}
