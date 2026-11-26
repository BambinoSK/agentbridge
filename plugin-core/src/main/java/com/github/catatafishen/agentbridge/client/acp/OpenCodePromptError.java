package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Describes an OpenCode {@code session/prompt} error for the user. OpenCode's error {@code data} is
 * {@code {service, errorName, status?, retryAfterMs?}}; it is OpenCode's own shape, not part of ACP.
 */
final class OpenCodePromptError {

    private static final String INTERNAL_ERROR_PREFIX = "Internal error: ";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);

    private OpenCodePromptError() {
    }

    /**
     * @return the message, the HTTP status and when to try again, or {@code null} when {@code data} is not
     * OpenCode's error shape
     */
    static @Nullable String describe(@NotNull String agentMessage, @Nullable JsonElement data,
                                     @NotNull Instant now, @NotNull ZoneId zone) {
        if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("errorName")) return null;
        JsonObject fields = data.getAsJsonObject();
        StringBuilder text = new StringBuilder(stripInternalErrorPrefix(agentMessage));
        Long status = longField(fields, "status");
        if (status != null) text.append(" (HTTP ").append(status).append(')');
        Long retryAfterMs = longField(fields, "retryAfterMs");
        if (retryAfterMs != null && retryAfterMs > 0) {
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '.') text.append(". ");
            else if (!text.isEmpty()) text.append(' ');
            text.append("Try again ").append(retryHint(Duration.ofMillis(retryAfterMs), now, zone)).append('.');
        }
        return text.toString();
    }

    private static String stripInternalErrorPrefix(String message) {
        if (!message.startsWith(INTERNAL_ERROR_PREFIX)) return message;
        String rest = message.substring(INTERNAL_ERROR_PREFIX.length()).strip();
        return rest.isEmpty() ? message : rest;
    }

    private static @Nullable Long longField(JsonObject fields, String name) {
        JsonElement value = fields.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        return value.getAsLong();
    }

    /**
     * Rounds the wait up, so the hint never names a time before the agent accepts the prompt again.
     */
    static @NotNull String retryHint(@NotNull Duration wait, @NotNull Instant now, @NotNull ZoneId zone) {
        long seconds = (wait.toMillis() + 999) / 1000;
        if (seconds < 60) return "in about " + seconds + " s";
        ZonedDateTime from = now.atZone(zone);
        ZonedDateTime at = from.plus(wait);
        DateTimeFormatter format = at.toLocalDate().equals(from.toLocalDate()) ? TIME : DATE_TIME;
        return "in about " + hoursAndMinutes((seconds + 59) / 60) + " (" + format.format(at) + ")";
    }

    private static String hoursAndMinutes(long minutes) {
        if (minutes < 60) return minutes + " min";
        long rest = minutes % 60;
        return (minutes / 60) + " h" + (rest == 0 ? "" : " " + rest + " min");
    }
}
