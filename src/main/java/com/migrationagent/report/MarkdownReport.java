package com.migrationagent.report;

import com.migrationagent.orchestrator.RunView;
import com.migrationagent.orchestrator.ServiceView;

import java.time.Duration;

public final class MarkdownReport {
    private MarkdownReport() {}

    public static String render(RunView v) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Java migration run ").append(v.id()).append("\n\n");
        sb.append("- Status: **").append(v.status()).append("**\n");
        sb.append("- Services: ").append(v.total()).append(" total, ").append(v.succeeded()).append(" succeeded, ")
                .append(v.failed()).append(" failed, ").append(v.skipped()).append(" skipped\n");
        if (v.startedAt() != null && v.finishedAt() != null) {
            sb.append("- Wall time: ").append(pretty(Duration.between(v.startedAt(), v.finishedAt()).toMillis())).append("\n");
        }
        sb.append("\n| Service | Status | Repairs | LLM calls | Time | PR / reason |\n|---|---|---|---|---|---|\n");
        for (ServiceView s : v.services()) {
            String detail = s.prUrl() != null ? s.prUrl() : s.reason() == null ? "" : oneLine(s.reason());
            sb.append("| ").append(s.name()).append(" | ").append(s.status()).append(" | ").append(s.repairAttempts())
                    .append(" | ").append(s.llmCalls()).append(" | ").append(pretty(s.durationMs())).append(" | ")
                    .append(detail).append(" |\n");
        }
        sb.append("\n## Needs a human\n");
        boolean any = false;
        for (ServiceView s : v.services()) {
            switch (s.status()) {
                case FAILED -> {
                    any = true;
                    sb.append("- **").append(s.name()).append("** failed: ").append(oneLine(s.reason())).append('\n');
                }
                case SKIPPED -> {
                    any = true;
                    sb.append("- **").append(s.name()).append("** skipped: ").append(oneLine(s.reason())).append('\n');
                }
                default -> { }
            }
        }
        if (!any) {
            sb.append("Nothing. Every service migrated.\n");
        }
        return sb.toString();
    }

    private static String pretty(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('|', '/').trim();
    }
}
