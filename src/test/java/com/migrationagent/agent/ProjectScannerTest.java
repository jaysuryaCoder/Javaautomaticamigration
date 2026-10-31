package com.migrationagent.agent;

import com.migrationagent.model.ProjectInfo;
import com.migrationagent.model.ServiceDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScannerTest {

    private static final ServiceDef SVC = new ServiceDef("svc", "https://example.com/svc.git");

    @Test
    void readsJavaAndBootVersionsFromAMavenPom() throws IOException {
        Path dir = Files.createTempDirectory("scan");
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.5</version>
                  </parent>
                  <properties><java.version>17</java.version></properties>
                  <dependencies><dependency><artifactId>lombok</artifactId></dependency></dependencies>
                </project>
                """);

        ProjectInfo info = ProjectScanner.scan(dir, SVC);

        assertEquals("MAVEN", info.buildTool());
        assertEquals("17", info.javaVersion());
        assertEquals("3.1.5", info.springBootVersion());
        assertTrue(info.lombok());
        assertTrue(info.warnings().stream().anyMatch(w -> w.contains("Spring Boot 3.1.5")));
    }

    @Test
    void readsGradleBuilds() throws IOException {
        Path dir = Files.createTempDirectory("scan");
        Files.writeString(dir.resolve("build.gradle.kts"), """
                plugins { id("org.springframework.boot") version "3.5.0" }
                java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
                """);

        ProjectInfo info = ProjectScanner.scan(dir, SVC);

        assertEquals("GRADLE", info.buildTool());
        assertEquals("17", info.javaVersion());
        assertEquals("3.5.0", info.springBootVersion());
        assertFalse(info.warnings().stream().anyMatch(w -> w.contains("predates")));
    }

    @Test
    void warnsWhenThereIsNoBuildFile() throws IOException {
        ProjectInfo info = ProjectScanner.scan(Files.createTempDirectory("scan"), SVC);
        assertEquals("UNKNOWN", info.buildTool());
        assertFalse(info.warnings().isEmpty());
    }
}
