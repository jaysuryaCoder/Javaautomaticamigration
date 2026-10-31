package com.migrationagent.tools;

import com.migrationagent.model.BuildResult;
import com.migrationagent.model.ServiceDef;

import java.io.IOException;
import java.nio.file.Path;

/** Compiles and tests the service on the target JDK (25). */
public interface BuildRunner {
    BuildResult build(Path projectDir, ServiceDef svc) throws IOException;
}
