package com.migrationagent.tools;

import com.migrationagent.model.ServiceDef;

import java.io.IOException;
import java.nio.file.Path;

public interface GitOps {

    /** Clones the service into {@code workRoot/<name>}, creates {@code branch}, returns the project directory. */
    Path checkout(ServiceDef svc, Path workRoot, String branch) throws IOException;

    /** Commits everything; returns false when there was nothing to commit. */
    boolean commitAll(Path projectDir, String message) throws IOException;

    void push(Path projectDir, String branch) throws IOException;

    /** Returns the PR URL, or null when no PR could be opened (e.g. the gh CLI is missing). */
    String openPullRequest(Path projectDir, ServiceDef svc, String branch, String title, String body) throws IOException;
}
