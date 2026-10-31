package com.migrationagent.agent;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies the LLM's edits. Format (one or more blocks):
 *
 * <pre>
 * FILE: relative/path/Foo.java
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH
 * exact existing text
 * =======
 * replacement text
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * </pre>
 *
 * Search text must match exactly once. Anything unsafe or ambiguous is rejected and reported back to the
 * model on the next attempt, never applied silently.
 */
public final class PatchApplier {
    private PatchApplier() {}

    public static final int MAX_BLOCKS = 40;
    private static final String SEARCH = "<<<<<<< SEARCH";
    private static final String DIVIDER = "=======";
    private static final String REPLACE = ">>>>>>> REPLACE";

    public record Result(List<String> applied, List<String> rejected, Set<String> files, String giveUpReason) {}

    private record Block(String file, String search, String replace) {}

    public static Result apply(Path root, String reply) {
        Path base = root.toAbsolutePath().normalize();
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> files = new TreeSet<>();
        String giveUp = null;

        List<Block> blocks = new ArrayList<>();
        String[] lines = reply == null ? new String[0] : reply.split("\\R", -1);
        String file = null;
        int i = 0;
        while (i < lines.length) {
            String t = lines[i].strip();
            if (t.startsWith("NO_CHANGES_POSSIBLE")) {
                String why = t.substring("NO_CHANGES_POSSIBLE".length()).replaceFirst("^[:\\s-]+", "");
                giveUp = why.isBlank() ? "model reported no safe change" : why;
                i++;
            } else if (t.startsWith("FILE:")) {
                file = t.substring(5).strip().replaceAll("^[`'\"]+|[`'\"]+$", "");
                i++;
            } else if (t.equals(SEARCH)) {
                StringBuilder search = new StringBuilder();
                StringBuilder replace = new StringBuilder();
                int nSearch = 0;
                int nReplace = 0;
                i++;
                while (i < lines.length && !lines[i].strip().equals(DIVIDER)) {
                    search.append(nSearch++ > 0 ? "\n" : "").append(lines[i++]);
                }
                if (i >= lines.length) {
                    rejected.add("Unterminated SEARCH block for " + file);
                    break;
                }
                i++;
                while (i < lines.length && !lines[i].strip().equals(REPLACE)) {
                    replace.append(nReplace++ > 0 ? "\n" : "").append(lines[i++]);
                }
                if (i >= lines.length) {
                    rejected.add("Unterminated REPLACE block for " + file);
                    break;
                }
                i++;
                blocks.add(new Block(file, search.toString(), replace.toString()));
            } else {
                i++;
            }
        }

        if (blocks.size() > MAX_BLOCKS) {
            rejected.add("Too many edit blocks (" + blocks.size() + " > " + MAX_BLOCKS + "); none applied");
            return new Result(applied, rejected, files, giveUp);
        }
        for (Block b : blocks) {
            String error = applyBlock(base, b);
            if (error == null) {
                applied.add(b.file());
                files.add(b.file());
            } else {
                rejected.add(b.file() + ": " + error);
            }
        }
        return new Result(applied, rejected, files, giveUp);
    }

    /** Returns null on success, otherwise the reason the block was rejected. */
    private static String applyBlock(Path base, Block b) {
        if (b.file() == null || b.file().isBlank()) {
            return "edit block without a FILE: header";
        }
        Path target = base.resolve(b.file()).normalize();
        if (!target.startsWith(base)) {
            return "path escapes the project directory";
        }
        if (isProtected(base.relativize(target))) {
            return "protected path (git metadata, secrets or keys)";
        }
        try {
            boolean exists = Files.isRegularFile(target);
            if (b.search().isBlank()) {
                if (exists) {
                    return "empty SEARCH but the file already exists; quote the text you want to replace";
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, b.replace());
                return null;
            }
            if (!exists) {
                return "file does not exist";
            }
            String content = Files.readString(target);
            String search = b.search();
            String replace = b.replace();
            int count = count(content, search);
            if (count == 0 && content.contains("\r\n")) {
                search = search.replace("\n", "\r\n");
                replace = replace.replace("\n", "\r\n");
                count = count(content, search);
            }
            if (count == 0) {
                return "SEARCH text not found (it must match the file exactly)";
            }
            if (count > 1) {
                return "SEARCH text is ambiguous (" + count + " matches); include more surrounding lines";
            }
            Files.writeString(target, content.replace(search, replace));
            return null;
        } catch (CharacterCodingException e) {
            return "file is not valid UTF-8 text";
        } catch (IOException e) {
            return "I/O error: " + e.getMessage();
        }
    }

    private static boolean isProtected(Path relative) {
        for (Path part : relative) {
            String n = part.toString();
            if (n.equals(".git")) {
                return true;
            }
        }
        String name = relative.getFileName().toString().toLowerCase();
        return name.equals(".env") || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key")
                || name.endsWith(".p12") || name.endsWith(".jks");
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
