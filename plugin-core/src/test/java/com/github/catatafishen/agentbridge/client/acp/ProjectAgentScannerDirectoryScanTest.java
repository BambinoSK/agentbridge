package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.AbstractClient.AgentMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectAgentScannerDirectoryScanTest {

    @TempDir
    Path tempDir;

    @Test
    void scanAgentDirectoriesPrefersEarlierDirsAndExcludesBuiltIns() throws Exception {
        Files.createDirectories(tempDir.resolve("custom-primary"));
        Files.createDirectories(tempDir.resolve("custom-secondary"));
        Files.writeString(
            tempDir.resolve("custom-primary/shared.md"),
            "---\nname: Primary\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("custom-secondary/shared.md"),
            "---\nname: Secondary\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("custom-secondary/extra.md"),
            "plain markdown agent",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("custom-secondary/build.md"),
            "---\nname: Build Shadow\n---\n",
            StandardCharsets.UTF_8
        );

        List<AgentMode> agents = ProjectAgentScanner.scanAgentDirectories(
            tempDir,
            Set.of("build"),
            "custom-primary",
            "custom-secondary"
        );

        assertEquals(List.of("shared", "extra"), agents.stream().map(AgentMode::slug).toList());
        assertEquals("Primary", agents.get(0).name());
        assertEquals("extra", agents.get(1).name());
    }

    @Test
    void scanGlobalAgentsListsUserLevelFilesAndSkipsExcludedSlugs() throws Exception {
        Files.writeString(
            tempDir.resolve("global-only.md"),
            "---\nname: Global Only\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("build.md"),
            "---\nname: Build Shadow\n---\n",
            StandardCharsets.UTF_8
        );

        List<AgentMode> agents = ProjectAgentScanner.scanGlobalAgents(
            List.of(tempDir), Set.of("build"));

        assertEquals(List.of("global-only"), agents.stream().map(AgentMode::slug).toList());
        assertEquals("Global Only", agents.getFirst().name());
    }

    @Test
    void scanGlobalAgentsToleratesMissingDirectories() {
        List<AgentMode> agents = ProjectAgentScanner.scanGlobalAgents(
            List.of(tempDir.resolve("does-not-exist"), tempDir.resolve("also-missing")),
            Set.of("build"));
        assertEquals(List.of(), agents);
    }

    @Test
    void scanGlobalAgentsEarlierDirectoryWinsOnSlugCollision() throws Exception {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("dupe.md"), "---\nname: From First\n---\n", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("dupe.md"), "---\nname: From Second\n---\n", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("extra.md"), "---\nname: Extra\n---\n", StandardCharsets.UTF_8);

        List<AgentMode> agents = ProjectAgentScanner.scanGlobalAgents(
            List.of(first, second), Set.of());

        assertEquals(List.of("dupe", "extra"), agents.stream().map(AgentMode::slug).toList());
        assertEquals("From First", agents.getFirst().name());
    }
}
