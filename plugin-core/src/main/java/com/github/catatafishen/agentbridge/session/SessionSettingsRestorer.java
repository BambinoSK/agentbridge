package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.Model;
import com.github.catatafishen.agentbridge.services.AgentUiSettings;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Applies the user's saved model and session options (effort, mode, ...) to an agent session, once per
 * session id.
 *
 * <p>An agent starts a session with its own defaults; it does not know what the user last picked in
 * AgentBridge. Two callers share one instance so the settings reach the session exactly once: the chat
 * after connecting, for a session the agent already has, so the toolbar shows the real values before the
 * first prompt; and the first prompt, for a client that creates its session only then (Claude CLI).
 * Only the first call for a session id applies them; a later id (a restarted process, a new
 * conversation) gets them again.</p>
 */
public final class SessionSettingsRestorer {

    private static final Logger LOG = Logger.getInstance(SessionSettingsRestorer.class);

    /** The session the settings were last applied to; guarded by {@code this}. */
    private @Nullable String restoredSessionId;

    /**
     * What the user saved for the active agent profile.
     *
     * @param model       the saved model id, or null/empty for none
     * @param optionValue the saved value of a session option key, empty for none
     */
    public record Saved(@Nullable String model, @NotNull UnaryOperator<String> optionValue) {
        public static @NotNull Saved from(@NotNull AgentUiSettings settings) {
            return new Saved(settings.getSelectedModel(), settings::getSessionOptionValue);
        }
    }

    /**
     * Applies {@code saved} to {@code sessionId} unless this instance already did for that id.
     * Synchronized so that a prompt sent while the connect-time restore is still running waits for it
     * instead of racing it.
     *
     * @param onNewSession runs first, only when {@code sessionId} is new
     * @return true when the settings were applied by this call
     */
    public synchronized boolean restoreOnce(@NotNull AbstractClient client, @NotNull String sessionId,
                                            @NotNull Saved saved, @NotNull Runnable onNewSession) {
        if (sessionId.equals(restoredSessionId)) return false;
        restoredSessionId = sessionId;
        onNewSession.run();
        restoreModel(client, sessionId, saved.model());
        restoreOptions(client, sessionId, saved.optionValue());
        return true;
    }

    /** Forgets the restored session, so its id gets the settings again; for when the session is dropped. */
    public synchronized void forget() {
        restoredSessionId = null;
    }

    private static void restoreModel(@NotNull AbstractClient client, @NotNull String sessionId,
                                     @Nullable String savedModel) {
        if (savedModel == null || savedModel.isEmpty()) return;
        List<Model> available;
        try {
            available = client.getAvailableModels();
        } catch (Exception e) {
            LOG.warn("Failed to retrieve available models for model validation — skipping setModel validation", e);
            available = List.of();
        }
        // An agent that lists no models cannot be checked, so the saved one is sent as is.
        if (!available.isEmpty() && available.stream().noneMatch(m -> savedModel.equals(m.id()))) {
            LOG.warn("Saved model '" + savedModel + "' is not in the available models list — skipping setModel to avoid empty turn");
            return;
        }
        try {
            client.setModel(sessionId, savedModel);
        } catch (Exception e) {
            LOG.warn("Failed to set model " + savedModel + " on new session", e);
        }
    }

    private static void restoreOptions(@NotNull AbstractClient client, @NotNull String sessionId,
                                       @NotNull UnaryOperator<String> optionValue) {
        for (SessionOption option : client.listSessionOptions()) {
            String value = Objects.requireNonNullElse(optionValue.apply(option.key()), "");
            if (value.isEmpty()) continue;
            try {
                client.setSessionOption(sessionId, option.key(), value);
            } catch (Exception e) {
                LOG.warn("Failed to restore session option " + option.key() + "=" + value, e);
            }
        }
    }
}
