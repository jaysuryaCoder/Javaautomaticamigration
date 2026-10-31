package com.migrationagent.infra;

import com.migrationagent.model.BuildTools;
import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.RewriteResult;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.tools.RewriteRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs the OpenRewrite Java-migration recipe without touching the service's own build file: Maven via the
 * rewrite-maven-plugin on the command line, Gradle via a throw-away init script.
 */
public final class OpenRewriteRunner implements RewriteRunner {

    private final MigrationSettings cfg;

    public OpenRewriteRunner(MigrationSettings cfg) {
        this.cfg = cfg;
    }

    @Override
    public RewriteResult run(Path dir, ServiceDef svc) throws IOException {
        String tool = BuildTools.detect(dir, svc);
        List<String> cmd;
        Path initScript = null;
        switch (tool) {
            case "MAVEN" -> cmd = List.of(BuildEnvironment.maven(dir), "-B", "-ntp",
                    "org.openrewrite.maven:rewrite-maven-plugin:" + cfg.getRewritePluginVersion() + ":run",
                    "-Drewrite.recipeArtifactCoordinates=" + cfg.getRewriteRecipeCoordinates(),
                    "-Drewrite.activeRecipes=" + cfg.getRewriteRecipe());
            case "GRADLE" -> {
                initScript = Files.createTempFile("rewrite-init-", ".gradle");
                Files.writeString(initScript, gradleInitScript());
                cmd = List.of(BuildEnvironment.gradle(dir), "--no-daemon", "--console=plain",
                        "--init-script", initScript.toString(), "rewriteRun",
                        "-Drewrite.activeRecipe=" + cfg.getRewriteRecipe());
            }
            default -> {
                return new RewriteResult(false, "No pom.xml or build.gradle found", 0);
            }
        }
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, dir, BuildEnvironment.env(cfg),
                    Duration.ofMinutes(cfg.getRewriteTimeoutMinutes()), 20_000);
            return new RewriteResult(r.ok(), r.output(), r.millis());
        } finally {
            if (initScript != null) {
                Files.deleteIfExists(initScript);
            }
        }
    }

    private String gradleInitScript() {
        return """
                initscript {
                    repositories { maven { url "https://plugins.gradle.org/m2" } }
                    dependencies { classpath("org.openrewrite:plugin:latest.release") }
                }
                rootProject {
                    plugins.apply(org.openrewrite.gradle.RewritePlugin)
                    dependencies { rewrite("%s") }
                    afterEvaluate {
                        if (repositories.isEmpty()) { repositories { mavenCentral() } }
                    }
                }
                """.formatted(cfg.getRewriteRecipeCoordinates().replace("RELEASE", "latest.release"));
    }
}
