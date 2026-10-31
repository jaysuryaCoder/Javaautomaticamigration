package com.migrationagent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the user prompt for a repair round: failing log tail + the files the log points at. */
public final class RepairPromptBuilder {
    private RepairPromptBuilder() {}

    private static final Pattern FILE_IN_LOG = Pattern.compile(
            "(/[^\\s:()\\[\\]'\"]+?\\.(?:java|kt|xml|gradle|kts|properties|ya?ml))");
    private static final int MAX_FILES = 6;

    public static String build(MigrationState s, int maxContextChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service: ").append(s.service.getName()).append('\n');
        sb.append("Build tool: ").append(s.project.buildTool())
                .append(", detected Java: ").append(s.project.javaVersion())
                .append(", Spring Boot: ").append(s.project.springBootVersion()).append('\n');
        sb.append("Repair attempt ").append(s.repairAttempts).append(" of ").append(s.maxRepairAttempts).append("\n\n");

        if (!s.project.warnings().isEmpty()) {
            sb.append("Scanner warnings:\n");
            s.project.warnings().forEach(w -> sb.append("- ").append(w).append('\n'));
            sb.append('\n');
        }
        if (!s.lastRejections.isEmpty()) {
            sb.append("Edits from your previous reply were REJECTED, fix these first:\n");
            s.lastRejections.forEach(r -> sb.append("- ").append(r).append('\n'));
            sb.append('\n');
        }

        String log = s.lastBuild == null ? "" : s.lastBuild.tail(maxContextChars / 2);
        sb.append("## Build log (tail)\n```\n").append(log).append("\n```\n\n");

        Set<Path> files = referencedFiles(s.projectDir, log);
        addBuildFile(s.projectDir, files);
        int perFile = Math.max(2_000, (maxContextChars / 2) / Math.max(1, files.size()));
        for (Path f : files) {
            try {
                String content = Files.readString(f);
                if (content.length() > perFile) {
                    content = content.substring(0, perFile) + "\n...[file truncated]...";
                }
                sb.append("## File: ").append(s.projectDir.relativize(f)).append("\n```\n")
                        .append(content).append("\n```\n\n");
            } catch (IOException ignored) {
                // unreadable or binary: skip, the log still tells the model what failed
            }
        }
        sb.append("Reply with edit blocks only.\n");
        return sb.toString();
    }

    static Set<Path> referencedFiles(Path projectDir, String log) {
        Path base = projectDir.toAbsolutePath().normalize();
        Set<Path> found = new LinkedHashSet<>();
        Matcher m = FILE_IN_LOG.matcher(log);
        while (m.find() && found.size() < MAX_FILES) {
            Path p = Path.of(m.group(1)).normalize();
            if (p.startsWith(base) && Files.isRegularFile(p)) {
                found.add(p);
            }
        }
        return found;
    }

    private static void addBuildFile(Path projectDir, Set<Path> files) {
        for (String name : new String[]{"pom.xml", "build.gradle", "build.gradle.kts"}) {
            Path p = projectDir.resolve(name).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                files.add(p);
                return;
            }
        }
    }
}
