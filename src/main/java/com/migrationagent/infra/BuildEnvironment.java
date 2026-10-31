package com.migrationagent.infra;

import com.migrationagent.model.MigrationSettings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Environment and executable resolution shared by the build and rewrite runners. */
final class BuildEnvironment {
    private BuildEnvironment() {}

    /** Forces the target JDK (25) for Maven/Gradle regardless of what the orchestrator itself runs on. */
    static Map<String, String> env(MigrationSettings cfg) {
        Map<String, String> env = new HashMap<>();
        String jdk = cfg.getJdkHome();
        if (jdk != null && !jdk.isBlank()) {
            env.put("JAVA_HOME", jdk);
            env.put("PATH", Path.of(jdk, "bin") + File.pathSeparator + System.getenv("PATH"));
        }
        if (cfg.getMavenOpts() != null && !cfg.getMavenOpts().isBlank()) {
            env.put("MAVEN_OPTS", cfg.getMavenOpts());
        }
        env.put("CI", "true");
        return env;
    }

    static String maven(Path dir) {
        Path wrapper = dir.resolve("mvnw");
        return Files.isExecutable(wrapper) ? wrapper.toString() : "mvn";
    }

    static String gradle(Path dir) {
        Path wrapper = dir.resolve("gradlew");
        return Files.isExecutable(wrapper) ? wrapper.toString() : "gradle";
    }
}
