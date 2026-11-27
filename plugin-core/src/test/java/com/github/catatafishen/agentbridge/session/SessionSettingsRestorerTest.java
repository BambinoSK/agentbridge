package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.Model;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SessionSettingsRestorer")
class SessionSettingsRestorerTest {

    private static final SessionOption EFFORT = new SessionOption("effort", "Effort", List.of("default", "high"));
    private static final SessionOption MODE = new SessionOption("mode", "Mode", List.of("build", "plan"));

    private final SessionSettingsRestorer restorer = new SessionSettingsRestorer();
    private final AtomicInteger newSessions = new AtomicInteger();

    private static SessionSettingsRestorer.Saved saved(String model, Map<String, String> options) {
        return new SessionSettingsRestorer.Saved(model, key -> options.getOrDefault(key, ""));
    }

    private boolean restore(FakeClient client, String sessionId, SessionSettingsRestorer.Saved saved) {
        return restorer.restoreOnce(client, sessionId, saved, newSessions::incrementAndGet);
    }

    @Test
    @DisplayName("the first call for a session sends the saved model, then each saved option")
    void firstCallSendsModelThenOptions() {
        FakeClient client = new FakeClient(List.of("p/m1", "p/m2"), List.of(EFFORT, MODE));

        boolean applied = restore(client, "s1", saved("p/m2", Map.of("effort", "high", "mode", "plan")));

        assertTrue(applied);
        assertEquals(List.of("model s1 p/m2", "option s1 effort=high", "option s1 mode=plan"), client.calls);
        assertEquals(1, newSessions.get());
    }

    @Test
    @DisplayName("a second call for the same session sends nothing, so the first prompt after connecting does not repeat it")
    void sameSessionIsRestoredOnce() {
        FakeClient client = new FakeClient(List.of("p/m1"), List.of(EFFORT));
        SessionSettingsRestorer.Saved saved = saved("p/m1", Map.of("effort", "high"));
        restore(client, "s1", saved);
        client.calls.clear();

        boolean applied = restore(client, "s1", saved);

        assertFalse(applied);
        assertEquals(List.of(), client.calls);
        assertEquals(1, newSessions.get());
    }

    @Test
    @DisplayName("a new session id gets the settings again")
    void newSessionIdIsRestored() {
        FakeClient client = new FakeClient(List.of(), List.of(EFFORT));
        SessionSettingsRestorer.Saved saved = saved(null, Map.of("effort", "high"));
        restore(client, "s1", saved);
        client.calls.clear();

        assertTrue(restore(client, "s2", saved));
        assertEquals(List.of("option s2 effort=high"), client.calls);
        assertEquals(2, newSessions.get());
    }

    @Test
    @DisplayName("after forget, the same session id gets the settings again")
    void forgetAllowsRestoringTheSameIdAgain() {
        FakeClient client = new FakeClient(List.of(), List.of(EFFORT));
        SessionSettingsRestorer.Saved saved = saved(null, Map.of("effort", "high"));
        restore(client, "s1", saved);
        client.calls.clear();

        restorer.forget();

        assertTrue(restore(client, "s1", saved));
        assertEquals(List.of("option s1 effort=high"), client.calls);
    }

    @Test
    @DisplayName("a saved model the agent does not list is not sent, but the options still are")
    void unknownSavedModelIsSkipped() {
        FakeClient client = new FakeClient(List.of("p/m1"), List.of(EFFORT));

        restore(client, "s1", saved("p/gone", Map.of("effort", "high")));

        assertEquals(List.of("option s1 effort=high"), client.calls);
    }

    @Test
    @DisplayName("a saved model is sent as is when the agent lists no models to check it against")
    void savedModelIsSentWhenNoModelsAreListed() {
        FakeClient client = new FakeClient(List.of(), List.of());

        restore(client, "s1", saved("p/m1", Map.of()));

        assertEquals(List.of("model s1 p/m1"), client.calls);
    }

    @Test
    @DisplayName("options with no saved value and an empty saved model send nothing")
    void emptySavedValuesSendNothing() {
        FakeClient client = new FakeClient(List.of("p/m1"), List.of(EFFORT, MODE));

        boolean applied = restore(client, "s1", saved("", Map.of()));

        assertTrue(applied);
        assertEquals(List.of(), client.calls);
    }

    @Test
    @DisplayName("a failing model switch does not stop the options from being restored")
    void failingSetModelStillRestoresOptions() {
        FakeClient client = new FakeClient(List.of("p/m1"), List.of(EFFORT, MODE));
        client.failSetModel = true;

        restore(client, "s1", saved("p/m1", Map.of("effort", "high", "mode", "plan")));

        assertEquals(List.of("option s1 effort=high", "option s1 mode=plan"), client.calls);
    }

    @Test
    @DisplayName("one failing option does not stop the next one")
    void failingOptionDoesNotStopTheNext() {
        FakeClient client = new FakeClient(List.of(), List.of(EFFORT, MODE));
        client.failingOption = "effort";

        restore(client, "s1", saved(null, Map.of("effort", "high", "mode", "plan")));

        assertEquals(List.of("option s1 mode=plan"), client.calls);
    }

    private static final class FakeClient extends AbstractClient {
        final List<String> calls = new ArrayList<>();
        private final List<Model> models;
        private final List<SessionOption> options;
        boolean failSetModel;
        String failingOption;

        FakeClient(List<String> modelIds, List<SessionOption> options) {
            this.models = modelIds.stream().map(id -> new Model(id, id, null, null)).toList();
            this.options = options;
        }

        @Override
        public String agentId() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public void start() {
            // nothing to start
        }

        @Override
        public void stop() {
            // nothing to stop
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String createSession(String cwd) {
            return "s1";
        }

        @Override
        public void cancelSession(String sessionId) {
            // no turn to cancel
        }

        @Override
        public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) {
            throw new UnsupportedOperationException("not used by the restorer");
        }

        @Override
        public List<Model> getAvailableModels() {
            return models;
        }

        @Override
        public void setModel(String sessionId, String modelId) {
            if (failSetModel) throw new IllegalStateException("model switch failed");
            calls.add("model " + sessionId + " " + modelId);
        }

        @Override
        public @NotNull List<SessionOption> listSessionOptions() {
            return options;
        }

        @Override
        public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
            if (key.equals(failingOption)) throw new IllegalStateException("option rejected");
            calls.add("option " + sessionId + " " + key + "=" + value);
        }
    }
}
