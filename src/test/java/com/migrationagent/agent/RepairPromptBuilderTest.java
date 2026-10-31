package com.migrationagent.agent;

import com.migrationagent.model.BuildResult;
import com.migrationagent.model.ProjectInfo;
import com.migrationagent.model.ServiceDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairPromptBuilderTest {

    @Test
    void includesFailingFilesBuildFileAndPreviousRejections() throws IOException {
        Path dir = Files.createTempDirectory("prompt").toAbsolutePath().normalize();
        Path src = dir.resolve("src/main/java/Foo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class Foo { void bar() { oldCall(); } }");
        Files.writeString(dir.resolve("pom.xml"), "<project>pom-marker</project>");

        MigrationState s = new MigrationState("run1", new ServiceDef("svc", "https://example.com/svc.git"), 3);
        s.projectDir = dir;
        s.project = new ProjectInfo("MAVEN", "17", "3.2.4", false, List.of("Spring Boot 3.2.4 predates Java 25"));
        s.repairAttempts = 2;
        s.lastRejections = List.of("Foo.java: SEARCH text not found");
        s.lastBuild = new BuildResult(false, 1, false,
                "[ERROR] " + src + ":[1,30] cannot find symbol oldCall\n[ERROR] also /etc/passwd:[1,1] nope", 10);

        String prompt = RepairPromptBuilder.build(s, 60_000);

        assertTrue(prompt.contains("oldCall(); } }"), "failing source file should be included");
        assertTrue(prompt.contains("pom-marker"), "build file should be included");
        assertTrue(prompt.contains("SEARCH text not found"), "previous rejections should be fed back");
        assertTrue(prompt.contains("predates Java 25"), "scanner warnings should be included");
        assertTrue(prompt.contains("Repair attempt 2 of 3"));
        assertFalse(prompt.contains("root:"), "files outside the project must never be read");
    }
}
