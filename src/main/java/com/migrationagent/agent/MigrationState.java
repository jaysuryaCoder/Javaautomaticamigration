package com.migrationagent.agent;

import com.migrationagent.model.BuildResult;
import com.migrationagent.model.ProjectInfo;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.model.ServiceStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The state bag that flows through one service's graph. Each service gets its own instance and is
 * only touched by that service's thread, so plain fields are fine.
 */
public final class MigrationState {

    public final String runId;
    public final ServiceDef service;
    public final int maxRepairAttempts;

    public Path projectDir;
    public String branch;
    public ProjectInfo project;
    public BuildResult lastBuild;
    public int repairAttempts;
    public int fruitlessRepairs;
    public boolean giveUp;
    public int llmCalls;
    public List<String> lastRejections = List.of();
    public final Set<String> changedFiles = new TreeSet<>();
    public ServiceStatus status = ServiceStatus.RUNNING;
    public String failureReason;
    public String prUrl;

    private Consumer<String> noteSink = m -> {};

    public MigrationState(String runId, ServiceDef service, int maxRepairAttempts) {
        this.runId = runId;
        this.service = service;
        this.maxRepairAttempts = maxRepairAttempts;
    }

    /** Where progress notes go (the orchestrator turns them into live events). */
    public void onNote(Consumer<String> sink) {
        this.noteSink = sink;
    }

    public void note(String message) {
        noteSink.accept(message);
    }
}
