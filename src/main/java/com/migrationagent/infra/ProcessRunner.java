package com.migrationagent.infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs an external command with a hard timeout, keeping only the tail of its output. */
public final class ProcessRunner {
    private ProcessRunner() {}

    public record Result(int exitCode, String output, boolean timedOut, long millis) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    public static Result run(List<String> command, Path dir, Map<String, String> env, Duration timeout,
                             int maxOutputChars) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        long t0 = System.nanoTime();
        Process p = pb.start();
        p.getOutputStream().close(); // never let a tool block waiting for stdin

        StringBuilder buf = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                char[] chunk = new char[4096];
                int n;
                while ((n = r.read(chunk)) != -1) {
                    synchronized (buf) {
                        buf.append(chunk, 0, n);
                        if (buf.length() > maxOutputChars * 2) {
                            buf.delete(0, buf.length() - maxOutputChars);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process was killed; whatever we captured is enough
            }
        });

        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.get(0), e);
        }
        if (!finished) {
            kill(p);
        }
        try {
            reader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (buf) {
            out = buf.length() > maxOutputChars ? buf.substring(buf.length() - maxOutputChars) : buf.toString();
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        return new Result(finished ? p.exitValue() : -1, out, !finished, millis);
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
