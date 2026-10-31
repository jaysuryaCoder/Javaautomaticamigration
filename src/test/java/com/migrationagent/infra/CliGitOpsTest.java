package com.migrationagent.infra;

import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.ServiceDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real git against a local "remote": clone, branch, commit, push. Needs the git CLI. */
class CliGitOpsTest {

    private static String git(Path dir, String... args) throws IOException {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var r = ProcessRunner.run(cmd, dir, Map.of(), Duration.ofSeconds(30), 10_000);
        assertTrue(r.ok(), "git " + String.join(" ", args) + " failed: " + r.output());
        return r.output();
    }

    private static Path remoteWithAMonorepoService() throws IOException {
        Path origin = Files.createTempDirectory("origin");
        git(origin, "init", "-b", "main");
        git(origin, "config", "user.name", "t");
        git(origin, "config", "user.email", "t@example.com");
        Files.createDirectories(origin.resolve("services/orders"));
        Files.writeString(origin.resolve("services/orders/pom.xml"), "<project/>");
        git(origin, "add", "-A");
        git(origin, "commit", "-m", "init");
        return origin;
    }

    @Test
    void checkoutCommitAndPushRoundTrip() throws IOException {
        Path origin = remoteWithAMonorepoService();
        var svc = new ServiceDef("orders", origin.toString());
        svc.setSubDir("services/orders");
        var ops = new CliGitOps(new MigrationSettings());

        Path project = ops.checkout(svc, Files.createTempDirectory("work"), "migrate/java-25");

        assertTrue(project.endsWith("services/orders"), "returns the sub-directory of a monorepo");
        assertTrue(git(project, "rev-parse", "--abbrev-ref", "HEAD").strip().equals("migrate/java-25"));

        assertFalse(ops.commitAll(project, "nothing"), "no changes means no commit");
        Files.writeString(project.resolve("pom.xml"), "<project><java.version>25</java.version></project>");
        assertTrue(ops.commitAll(project, "chore: migrate to Java 25"));
        assertFalse(ops.commitAll(project, "again"), "second commit has nothing to do");

        ops.push(project, "migrate/java-25");
        assertTrue(git(origin, "branch", "--list", "migrate/java-25").contains("migrate/java-25"));
        assertTrue(git(origin, "log", "migrate/java-25", "--format=%s", "-1").contains("migrate to Java 25"));
        assertNotNull(ops);
    }

    @Test
    void badSubDirAndBadRepoFailLoudly() throws IOException {
        Path origin = remoteWithAMonorepoService();
        var ops = new CliGitOps(new MigrationSettings());

        var wrongDir = new ServiceDef("orders", origin.toString());
        wrongDir.setSubDir("services/missing");
        assertThrows(IOException.class, () -> ops.checkout(wrongDir, Files.createTempDirectory("work"), "b"));

        var wrongRepo = new ServiceDef("ghost", "/definitely/not/a/repo");
        var ex = assertThrows(IOException.class, () -> ops.checkout(wrongRepo, Files.createTempDirectory("work"), "b"));
        assertTrue(ex.getMessage().contains("git clone failed"));
        assertEquals(true, ex.getMessage().length() > 10);
    }
}
