package com.migrationagent.tools;

import com.migrationagent.model.RewriteResult;
import com.migrationagent.model.ServiceDef;

import java.io.IOException;
import java.nio.file.Path;

/** Deterministic, recipe-based migration step (OpenRewrite). Runs before the LLM is ever involved. */
public interface RewriteRunner {
    RewriteResult run(Path projectDir, ServiceDef svc) throws IOException;
}
