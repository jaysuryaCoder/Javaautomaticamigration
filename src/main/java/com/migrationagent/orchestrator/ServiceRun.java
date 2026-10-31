package com.migrationagent.orchestrator;

import com.migrationagent.agent.MigrationState;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.model.ServiceStatus;

import java.util.ArrayList;
import java.util.List;

/** Live, thread-safe progress of one service inside a run. */
public final class ServiceRun {

    private final ServiceDef def;
    private volatile ServiceStatus status = ServiceStatus.PENDING;
    private volatile String node;
    private volatile int repairAttempts;
    private volatile int llmCalls;
    private volatile List<String> changedFiles = List.of();
    private volatile String prUrl;
    private volatile String reason;
    private volatile long startedAtMs;
    private volatile long finishedAtMs;

    ServiceRun(ServiceDef def) {
        this.def = def;
    }

    void started() {
        startedAtMs = System.currentTimeMillis();
        status = ServiceStatus.RUNNING;
    }

    void node(String node) {
        this.node = node;
    }

    void progress(MigrationState s) {
        repairAttempts = s.repairAttempts;
        llmCalls = s.llmCalls;
        changedFiles = new ArrayList<>(s.changedFiles);
    }

    void finished(MigrationState s) {
        progress(s);
        status = s.status;
        prUrl = s.prUrl;
        reason = s.failureReason;
        node = null;
        finishedAtMs = System.currentTimeMillis();
    }

    void skipped(String why) {
        status = ServiceStatus.SKIPPED;
        reason = why;
        node = null;
    }

    ServiceStatus status() {
        return status;
    }

    ServiceView view() {
        long end = finishedAtMs != 0 ? finishedAtMs : System.currentTimeMillis();
        long duration = startedAtMs == 0 ? 0 : end - startedAtMs;
        return new ServiceView(def.getName(), status, node, repairAttempts, llmCalls, changedFiles, prUrl, reason,
                duration, List.copyOf(def.getDependsOn()));
    }
}
