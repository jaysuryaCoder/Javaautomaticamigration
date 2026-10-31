package com.migrationagent.model;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BuildTools {
    private BuildTools() {}

    /** Returns MAVEN or GRADLE, honouring an explicit choice on the service, otherwise sniffing the files. */
    public static String detect(Path projectDir, ServiceDef svc) {
        String configured = svc.getBuildTool() == null ? "AUTO" : svc.getBuildTool().toUpperCase();
        if (!configured.equals("AUTO")) {
            return configured;
        }
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            return "MAVEN";
        }
        if (Files.exists(projectDir.resolve("build.gradle")) || Files.exists(projectDir.resolve("build.gradle.kts"))) {
            return "GRADLE";
        }
        return "UNKNOWN";
    }
}
