package com.migrationagent.model;

public record BuildResult(boolean success, int exitCode, boolean timedOut, String log, long millis) {

    /** Last {@code maxChars} characters of the log: compiler/test errors are at the end. */
    public String tail(int maxChars) {
        if (log == null) {
            return "";
        }
        return log.length() <= maxChars ? log : "...[truncated]...\n" + log.substring(log.length() - maxChars);
    }
}
