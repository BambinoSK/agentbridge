package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link SessionOption#effectiveValue(String)}: the session options menu
 * tick-marks the agent-reported live value when the agent has one (kept fresh
 * by the session/set_config_option echo on OpenCode v2) and falls back to the
 * locally stored pick only before the agent has reported anything. This is
 * what makes the model-switch effort reset visible in the menu instead of a
 * stale stored selection.
 */
class SessionOptionEffectiveValueTest {

    private static SessionOption effort(String initialValue) {
        return new SessionOption("effort", "Effort",
            List.of("low", "medium", "high", "default"), null, initialValue);
    }

    @Test
    void liveAgentValueWinsOverStoredPick() {
        // User clicked "high" earlier (stored), then a model switch reset the
        // agent's effort to its default — the echo reports "default".
        assertEquals("default", effort("default").effectiveValue("high"));
    }

    @Test
    void liveAgentValueMatchesOwnClickAfterEcho() {
        // Click "high" -> echo reports "high": tick stays on the clicked value.
        assertEquals("high", effort("high").effectiveValue("high"));
    }

    @Test
    void storedPickIsFallbackBeforeAgentReports() {
        // No live value yet (pre-session or agent without echo): stored pick wins.
        assertEquals("high", effort(null).effectiveValue("high"));
    }

    @Test
    void emptyLiveValueFallsBackToStored() {
        // A blank live value carries no information; treat it as absent.
        assertEquals("high", effort("").effectiveValue("high"));
    }

    @Test
    void nothingStoredAndNothingLiveIsEmpty() {
        assertEquals("", effort(null).effectiveValue(null));
        assertEquals("", effort(null).effectiveValue(""));
    }
}
