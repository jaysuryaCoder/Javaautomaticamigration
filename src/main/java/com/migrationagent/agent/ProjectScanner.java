package com.migrationagent.agent;

import com.migrationagent.model.BuildTools;
import com.migrationagent.model.ProjectInfo;
import com.migrationagent.model.ServiceDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap, deterministic fingerprinting of a service before any build or LLM call. */
public final class ProjectScanner {
    private ProjectScanner() {}

    private static final Pattern MAVEN_JAVA = Pattern.compile(
            "<(?:maven\\.compiler\\.release|maven\\.compiler\\.source|java\\.version|release)>\\s*(\\d+)\\s*<");
    private static final Pattern BOOT_PARENT = Pattern.compile(
            "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>\\s*([^<\\s]+)\\s*<", Pattern.DOTALL);
    private static final Pattern GRADLE_JAVA = Pattern.compile(
            "(?:JavaVersion\\.VERSION_(\\d+)|JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)|sourceCompatibility\\s*=\\s*['\"]?(\\d+))");
    private static final Pattern GRADLE_BOOT = Pattern.compile(
            "org\\.springframework\\.boot['\"]?\\)?\\s*(?:version)?\\s*['\"]([0-9][^'\"]*)['\"]");

    public static ProjectInfo scan(Path dir, ServiceDef svc) throws IOException {
        String tool = BuildTools.detect(dir, svc);
        List<String> warnings = new ArrayList<>();
        String java = null;
        String boot = null;
        boolean lombok = false;

        if (tool.equals("MAVEN")) {
            String pom = read(dir.resolve("pom.xml"));
            java = first(MAVEN_JAVA, pom, 1);
            boot = first(BOOT_PARENT, pom, 1);
            lombok = pom.contains("<artifactId>lombok</artifactId>");
        } else if (tool.equals("GRADLE")) {
            String gradle = read(dir.resolve("build.gradle")) + "\n" + read(dir.resolve("build.gradle.kts"));
            Matcher m = GRADLE_JAVA.matcher(gradle);
            if (m.find()) {
                java = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            }
            boot = first(GRADLE_BOOT, gradle, 1);
            lombok = gradle.contains("lombok");
        } else {
            warnings.add("No pom.xml or build.gradle found in " + dir.getFileName());
        }

        if (boot != null && bootIsOlderThan(boot, 3, 5)) {
            warnings.add("Spring Boot " + boot + " predates official Java 25 support; the build will likely need a Boot upgrade");
        }
        if (lombok) {
            warnings.add("Lombok in use: needs a release that understands Java 25 class files");
        }
        return new ProjectInfo(tool, java, boot, lombok, warnings);
    }

    static boolean bootIsOlderThan(String version, int major, int minor) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) {
            return false;
        }
        int ma = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        return ma < major || (ma == major && mi < minor);
    }

    private static String first(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    private static String read(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p) : "";
    }
}
