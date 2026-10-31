package com.migrationagent.infra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses /bin/sh, so it runs on Linux and macOS (the platforms the agent is meant to run on). */
class ProcessRunnerTest {

    private static final Path DIR = Path.of(System.getProperty("java.io.tmpdir"));

    @Test
    void capturesOutputAndExitCode() throws IOException {
        var ok = ProcessRunner.run(List.of("sh", "-c", "echo hello; echo oops 1>&2"), DIR, Map.of(),
                Duration.ofSeconds(10), 10_000);
        assertTrue(ok.ok());
        assertTrue(ok.output().contains("hello") && ok.output().contains("oops"), "stderr is merged into the log");

        var bad = ProcessRunner.run(List.of("sh", "-c", "exit 3"), DIR, Map.of(), Duration.ofSeconds(10), 10_000);
        assertFalse(bad.ok());
        assertEquals(3, bad.exitCode());
    }

    @Test
    void killsRunawayProcessesAtTheTimeout() throws IOException {
        long t0 = System.currentTimeMillis();
        var r = ProcessRunner.run(List.of("sh", "-c", "sleep 30"), DIR, Map.of(), Duration.ofMillis(300), 10_000);
        long took = System.currentTimeMillis() - t0;

        assertTrue(r.timedOut());
        assertFalse(r.ok());
        assertTrue(took < 10_000, "should return shortly after the timeout, took " + took + " ms");
    }

    @Test
    void keepsOnlyTheTailOfHugeOutput() throws IOException {
        var r = ProcessRunner.run(List.of("sh", "-c", "head -c 500000 /dev/zero | tr '\\0' 'a'; echo THE-END"), DIR,
                Map.of(), Duration.ofSeconds(20), 2_000);
        assertTrue(r.output().length() <= 2_000);
        assertTrue(r.output().contains("THE-END"), "the end of the log is what matters for compiler errors");
    }

    @Test
    void stdinIsClosedSoToolsCannotHang() throws IOException {
        Path f = Files.createTempFile("stdin", ".txt");
        var r = ProcessRunner.run(List.of("sh", "-c", "cat > " + f + "; echo done"), DIR, Map.of(),
                Duration.ofSeconds(10), 1_000);
        assertTrue(r.ok(), "cat must see EOF immediately instead of waiting forever");
    }
}
