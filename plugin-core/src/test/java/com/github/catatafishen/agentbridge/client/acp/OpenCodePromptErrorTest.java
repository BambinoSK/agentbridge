package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("OpenCodePromptError")
class OpenCodePromptErrorTest {

    private static final Instant NOW = Instant.parse("2026-11-26T14:31:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;

    private static String describe(String message, String dataJson) {
        JsonElement data = dataJson == null ? null : JsonParser.parseString(dataJson);
        return OpenCodePromptError.describe(message, data, NOW, UTC);
    }

    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("rate limit names the HTTP status and when to try again")
        void rateLimitNamesStatusAndRetryTime() {
            assertEquals("Rate limit reached (HTTP 429). Try again in about 1 min (14:32).",
                describe("Internal error: Rate limit reached",
                    "{\"service\":\"session\",\"errorName\":\"provider.rate-limit\",\"status\":429,\"retryAfterMs\":60000}"));
        }

        @Test
        @DisplayName("an error without retryAfterMs names only the status")
        void statusWithoutRetry() {
            assertEquals("Provider unavailable (HTTP 503)",
                describe("Internal error: Provider unavailable",
                    "{\"service\":\"session\",\"errorName\":\"provider.unavailable\",\"status\":503}"));
        }

        @Test
        @DisplayName("a command error drops the raw data")
        void commandErrorDropsRawData() {
            assertEquals("Usage: /review <target>",
                describe("Internal error: Usage: /review <target>",
                    "{\"service\":\"command\",\"errorName\":\"CommandExecutionError\"}"));
        }

        @Test
        @DisplayName("a message ending in a full stop gets no second one")
        void messageEndingInFullStop() {
            assertEquals("Free usage limit reached. Try again in about 3 h (17:31).",
                describe("Free usage limit reached.",
                    "{\"errorName\":\"provider.free-limit\",\"retryAfterMs\":10800000}"));
        }

        @Test
        @DisplayName("an empty message is only the retry hint")
        void emptyMessage() {
            assertEquals("Try again in about 5 s.", describe("", "{\"errorName\":\"x\",\"retryAfterMs\":5000}"));
        }

        @Test
        @DisplayName("a bare Internal error prefix is kept")
        void bareInternalErrorPrefixIsKept() {
            assertEquals("Internal error: ", describe("Internal error: ", "{\"errorName\":\"x\"}"));
        }

        @Test
        @DisplayName("non-numeric status and zero retryAfterMs are ignored")
        void nonNumericStatusAndZeroRetryIgnored() {
            assertEquals("Busy", describe("Busy", "{\"errorName\":\"x\",\"status\":\"429\",\"retryAfterMs\":0}"));
        }

        @Test
        @DisplayName("data without errorName is not OpenCode's shape")
        void dataWithoutErrorName() {
            assertNull(describe("Internal error", "{\"details\":\"stack\"}"));
        }

        @Test
        @DisplayName("missing or non-object data is not OpenCode's shape")
        void missingOrNonObjectData() {
            assertNull(describe("Internal error", null));
            assertNull(OpenCodePromptError.describe("Internal error", new JsonPrimitive("text"), NOW, UTC));
            assertNull(describe("Internal error", "[1,2]"));
        }
    }

    @Nested
    @DisplayName("retryHint")
    class RetryHint {

        private static String hint(long millis) {
            return OpenCodePromptError.retryHint(Duration.ofMillis(millis), NOW, UTC);
        }

        @Test
        @DisplayName("under a minute is in seconds, rounded up")
        void underAMinuteInSeconds() {
            assertEquals("in about 2 s", hint(1_500));
            assertEquals("in about 59 s", hint(59_000));
        }

        @Test
        @DisplayName("a minute or more is in minutes, rounded up, with the clock time")
        void minutesRoundedUp() {
            assertEquals("in about 1 min (14:32)", hint(60_000));
            assertEquals("in about 2 min (14:32)", hint(61_000));
            assertEquals("in about 59 min (15:30)", hint(59 * 60_000L));
        }

        @Test
        @DisplayName("an hour or more is in hours and minutes")
        void hoursAndMinutes() {
            assertEquals("in about 1 h (15:31)", hint(60 * 60_000L));
            assertEquals("in about 1 h 30 min (16:01)", hint(90 * 60_000L));
        }

        @Test
        @DisplayName("a retry time on another day names the date")
        void anotherDayNamesTheDate() {
            assertEquals("in about 10 h 29 min (Nov 27, 01:00)", hint((10 * 60 + 29) * 60_000L));
        }

        @Test
        @DisplayName("the clock time is in the given zone")
        void clockTimeInZone() {
            assertEquals("in about 1 min (15:32)",
                OpenCodePromptError.retryHint(Duration.ofMinutes(1), NOW, ZoneId.of("Europe/Bratislava")));
        }
    }
}
