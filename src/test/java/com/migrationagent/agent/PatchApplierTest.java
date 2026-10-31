package com.migrationagent.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchApplierTest {

    private static Path project(String file, String content) throws IOException {
        Path dir = Files.createTempDirectory("patch-test");
        Path f = dir.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return dir;
    }

    private static String block(String file, String search, String replace) {
        return "FILE: " + file + "\n<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
    }

    @Test
    void appliesAnExactUniqueMatch() throws IOException {
        Path dir = project("pom.xml", "<version>1.0</version>\n<other/>\n");
        var r = PatchApplier.apply(dir, block("pom.xml", "<version>1.0</version>", "<version>2.0</version>"));

        assertEquals(1, r.applied().size());
        assertTrue(r.rejected().isEmpty());
        assertEquals("<version>2.0</version>\n<other/>\n", Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void rejectsAmbiguousAndMissingSearchText() throws IOException {
        Path dir = project("A.java", "x();\nx();\n");
        var ambiguous = PatchApplier.apply(dir, block("A.java", "x();", "y();"));
        var missing = PatchApplier.apply(dir, block("A.java", "nope();", "y();"));

        assertTrue(ambiguous.applied().isEmpty());
        assertTrue(ambiguous.rejected().get(0).contains("ambiguous"));
        assertTrue(missing.rejected().get(0).contains("not found"));
        assertEquals("x();\nx();\n", Files.readString(dir.resolve("A.java")));
    }

    @Test
    void refusesPathTraversalAndProtectedFiles() throws IOException {
        Path dir = project("A.java", "x();\n");
        Path outside = Files.createTempFile("outside", ".txt");
        Files.writeString(outside, "secret");

        var traversal = PatchApplier.apply(dir, block("../" + outside.getFileName(), "secret", "pwned"));
        var gitDir = PatchApplier.apply(dir, block(".git/config", "", "evil"));
        var key = PatchApplier.apply(dir, block("deploy.pem", "", "evil"));

        assertEquals("secret", Files.readString(outside));
        assertTrue(traversal.rejected().get(0).contains("escapes"));
        assertTrue(gitDir.rejected().get(0).contains("protected"));
        assertTrue(key.rejected().get(0).contains("protected"));
    }

    @Test
    void handlesWindowsLineEndings() throws IOException {
        Path dir = project("A.java", "a();\r\nb();\r\n");
        var r = PatchApplier.apply(dir, block("A.java", "a();\nb();", "c();"));
        assertEquals(1, r.applied().size());
        assertEquals("c();\r\n", Files.readString(dir.resolve("A.java")));
    }

    @Test
    void createsNewFilesButNeverOverwritesExistingOnesWithEmptySearch() throws IOException {
        Path dir = project("A.java", "x();\n");
        var created = PatchApplier.apply(dir, block("src/New.java", "", "class New {}"));
        var overwrite = PatchApplier.apply(dir, block("A.java", "", "class Oops {}"));

        assertEquals("class New {}", Files.readString(dir.resolve("src/New.java")));
        assertFalse(overwrite.rejected().isEmpty());
        assertEquals("x();\n", Files.readString(dir.resolve("A.java")));
        assertEquals(1, created.applied().size());
    }

    @Test
    void reportsWhenTheModelGivesUp() throws IOException {
        Path dir = project("A.java", "x();\n");
        var r = PatchApplier.apply(dir, "NO_CHANGES_POSSIBLE: needs a vendor SDK upgrade");
        assertEquals("needs a vendor SDK upgrade", r.giveUpReason());
        assertTrue(r.applied().isEmpty());
    }

    @Test
    void appliesSeveralBlocksAcrossFilesAndKeepsGoingAfterABadOne() throws IOException {
        Path dir = project("A.java", "one();\n");
        Files.writeString(dir.resolve("B.java"), "two();\n");
        String reply = block("A.java", "one();", "uno();") + "\n"
                + block("A.java", "missing();", "x();") + "\n"
                + block("B.java", "two();", "dos();");
        var r = PatchApplier.apply(dir, reply);

        assertEquals(2, r.applied().size());
        assertEquals(1, r.rejected().size());
        assertEquals("uno();\n", Files.readString(dir.resolve("A.java")));
        assertEquals("dos();\n", Files.readString(dir.resolve("B.java")));
    }
}
