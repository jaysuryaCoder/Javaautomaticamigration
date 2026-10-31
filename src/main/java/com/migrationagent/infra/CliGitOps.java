package com.migrationagent.infra;

import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.tools.GitOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Git via the git CLI and pull requests via the GitHub CLI (gh). Credentials come from the environment the
 * orchestrator runs in (SSH agent, credential helper, GH_TOKEN); nothing is ever prompted for.
 */
public final class CliGitOps implements GitOps {

    private static final Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final MigrationSettings cfg;

    public CliGitOps(MigrationSettings cfg) {
        this.cfg = cfg;
    }

    @Override
    public Path checkout(ServiceDef svc, Path workRoot, String branch) throws IOException {
        Files.createDirectories(workRoot);
        Path repoDir = workRoot.resolve(svc.getName());
        git(workRoot, "clone", "--depth", "1", "--branch", svc.getBranch(), "--", svc.getRepoUrl(), repoDir.toString());
        git(repoDir, "checkout", "-B", branch);
        git(repoDir, "config", "user.name", cfg.getGitUserName());
        git(repoDir, "config", "user.email", cfg.getGitUserEmail());
        Path project = svc.getSubDir().isBlank() ? repoDir : repoDir.resolve(svc.getSubDir()).normalize();
        if (!project.startsWith(repoDir) || !Files.isDirectory(project)) {
            throw new IOException("subDir '" + svc.getSubDir() + "' not found in " + svc.getName());
        }
        return project;
    }

    @Override
    public boolean commitAll(Path projectDir, String message) throws IOException {
        git(projectDir, "add", "-A");
        ProcessRunner.Result status = run(projectDir, List.of("git", "status", "--porcelain"));
        if (status.output().isBlank()) {
            return false;
        }
        git(projectDir, "commit", "-m", message);
        return true;
    }

    @Override
    public void push(Path projectDir, String branch) throws IOException {
        git(projectDir, "push", "--set-upstream", "origin", branch);
    }

    @Override
    public String openPullRequest(Path projectDir, ServiceDef svc, String branch, String title, String body)
            throws IOException {
        List<String> cmd = List.of("gh", "pr", "create", "--base", svc.getBranch(), "--head", branch,
                "--title", title, "--body", body);
        try {
            ProcessRunner.Result r = run(projectDir, cmd);
            if (!r.ok()) {
                return null;
            }
            String[] lines = r.output().strip().split("\\R");
            String last = lines[lines.length - 1].strip();
            return last.startsWith("http") ? last : null;
        } catch (IOException gh) {
            return null; // gh not installed: the branch is pushed, a human can open the PR
        }
    }

    private void git(Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = run(dir, cmd);
        if (!r.ok()) {
            throw new IOException("git " + args[0] + " failed (exit " + r.exitCode() + "): " + lastLine(r.output()));
        }
    }

    private static ProcessRunner.Result run(Path dir, List<String> cmd) throws IOException {
        return ProcessRunner.run(cmd, dir, ENV, TIMEOUT, 20_000);
    }

    private static String lastLine(String out) {
        String[] lines = out.strip().split("\\R");
        return lines[lines.length - 1];
    }
}
