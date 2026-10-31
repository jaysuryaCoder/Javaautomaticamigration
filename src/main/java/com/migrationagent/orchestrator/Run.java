package com.migrationagent.orchestrator;

import com.migrationagent.model.ServiceDef;
import com.migrationagent.model.ServiceStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** One migration run across many services: live status, event history and subscribers. */
public final class Run {

    public enum Status { RUNNING, COMPLETED, COMPLETED_WITH_FAILURES, CANCELLED }

    private final String id;
    private final Instant startedAt = Instant.now();
    private volatile Instant finishedAt;
    private volatile Status status = Status.RUNNING;
    private volatile boolean cancelRequested;
    private volatile boolean closed;
    private final Map<String, ServiceRun> services = new LinkedHashMap<>();
    private final List<RunEvent> history = new ArrayList<>();
    private final List<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();

    Run(String id, List<ServiceDef> defs) {
        this.id = id;
        defs.forEach(d -> services.put(d.getName(), new ServiceRun(d)));
    }

    public String id() {
        return id;
    }

    public Status status() {
        return status;
    }

    /** True only once the final event is published and the report written (see {@link #close()}). */
    public boolean isFinished() {
        return closed;
    }

    void close() {
        closed = true;
    }

    public void cancel() {
        cancelRequested = true;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    ServiceRun service(String name) {
        return services.get(name);
    }

    void finish() {
        finishedAt = Instant.now();
        boolean problems = services.values().stream()
                .anyMatch(s -> s.status() == ServiceStatus.FAILED || s.status() == ServiceStatus.SKIPPED);
        status = cancelRequested ? Status.CANCELLED : problems ? Status.COMPLETED_WITH_FAILURES : Status.COMPLETED;
    }

    void publish(EventType type, String service, String node, String message) {
        publish(new RunEvent(Instant.now(), id, service, type, node, message));
    }

    synchronized void publish(RunEvent event) {
        history.add(event);
        for (Consumer<RunEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (RuntimeException ignored) {
                // a broken subscriber (closed SSE connection) must never break the run
            }
        }
    }

    /** Replays history to the new subscriber, then streams live events. Returns an unsubscribe handle. */
    public synchronized Runnable subscribe(Consumer<RunEvent> listener) {
        for (RunEvent e : history) {
            listener.accept(e);
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public RunView view() {
        List<ServiceView> views = new ArrayList<>();
        int ok = 0, failed = 0, skipped = 0, running = 0, pending = 0;
        for (ServiceRun s : services.values()) {
            views.add(s.view());
            switch (s.status()) {
                case SUCCEEDED -> ok++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case RUNNING -> running++;
                case PENDING -> pending++;
            }
        }
        return new RunView(id, status, startedAt, finishedAt, services.size(), ok, failed, skipped, running, pending, views);
    }
}
