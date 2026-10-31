package com.migrationagent.infra;

import com.migrationagent.model.BuildResult;
import com.migrationagent.model.BuildTools;
import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.tools.BuildRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Builds and tests with Maven or Gradle, on the JDK configured in {@code migration.jdk-home}. */
public final class ShellBuildRunner implements BuildRunner {

    private final MigrationSettings cfg;

    public ShellBuildRunner(MigrationSettings cfg) {
        this.cfg = cfg;
    }

    @Override
    public BuildResult build(Path dir, ServiceDef svc) throws IOException {
        String tool = BuildTools.detect(dir, svc);
        List<String> cmd = new ArrayList<>();
        switch (tool) {
            case "MAVEN" -> {
                cmd.addAll(List.of(BuildEnvironment.maven(dir), "-B", "-ntp", "-Dstyle.color=never"));
                if (cfg.isSkipTests()) {
                    cmd.add("-DskipTests");
                }
                cmd.addAll(List.of("clean", "verify"));
            }
            case "GRADLE" -> {
                cmd.addAll(List.of(BuildEnvironment.gradle(dir), "--no-daemon", "--console=plain", "build"));
                if (cfg.isSkipTests()) {
                    cmd.addAll(List.of("-x", "test"));
                }
            }
            default -> {
                return new BuildResult(false, -1, false, "No pom.xml or build.gradle found in " + dir, 0);
            }
        }
        ProcessRunner.Result r = ProcessRunner.run(cmd, dir, BuildEnvironment.env(cfg),
                Duration.ofMinutes(cfg.getBuildTimeoutMinutes()), 60_000);
        return new BuildResult(r.ok(), r.exitCode(), r.timedOut(), r.output(), r.millis());
    }
}
