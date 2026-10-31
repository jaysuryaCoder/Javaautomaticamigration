package com.migrationagent.orchestrator;

import com.migrationagent.agent.MigrationGraphFactory;
import com.migrationagent.agent.MigrationState;
import com.migrationagent.graph.StateGraph;
import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.model.ServiceStatus;
import com.migrationagent.report.MarkdownReport;
import com.migrationagent.tools.Toolbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Runs one agent graph per service, in parallel on virtual threads, wave by wave so shared libraries land
 * before the services that depend on them. A failed service skips its dependents instead of building them
 * against a broken parent.
 */
public final class MigrationOrchestrator {

    private final MigrationSettings cfg;
    private final RunRegistry registry;
    private final StateGraph.CompiledGraph<MigrationState> graph;

    public MigrationOrchestrator(Toolbox tools, MigrationSettings cfg, RunRegistry registry) {
        this.cfg = cfg;
        this.registry = registry;
        this.graph = MigrationGraphFactory.create(tools, cfg);
    }

    /** Starts a run asynchronously and returns immediately. Only one run may be active at a time. */
    public synchronized Run start(List<ServiceDef> services) {
        if (services.isEmpty()) {
            throw new IllegalArgumentException("No services to migrate");
        }
        services.forEach(ServiceDef::validate);
        List<List<ServiceDef>> waves = DependencyPlanner.plan(services);
        if (registry.active().isPresent()) {
            throw new IllegalStateException("Run " + registry.active().get().id() + " is still in progress");
        }
        Run run = new Run(UUID.randomUUID().toString().substring(0, 8), services);
        registry.add(run);
        Thread.ofPlatform().name("migration-run-" + run.id()).start(() -> execute(run, waves));
        return run;
    }

    /** Runs to completion on the calling thread (used by tests). */
    public Run startAndWait(List<ServiceDef> services) throws InterruptedException {
        Run run = start(services);
        while (!run.isFinished()) {
            Thread.sleep(25);
        }
        return run;
    }

    private void execute(Run run, List<List<ServiceDef>> waves) {
        try {
            int total = waves.stream().mapToInt(List::size).sum();
            run.publish(EventType.RUN_STARTED, null, null,
                    total + " services in " + waves.size() + " wave(s), up to " + cfg.getMaxParallel() + " in parallel");
            Set<String> blocked = ConcurrentHashMap.newKeySet();
            Semaphore permits = new Semaphore(cfg.getMaxParallel());

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                int waveNo = 0;
                for (List<ServiceDef> wave : waves) {
                    run.publish(EventType.WAVE_STARTED, null, null, "Wave " + (++waveNo) + ": " + names(wave));
                    List<Future<?>> futures = new ArrayList<>();
                    for (ServiceDef svc : wave) {
                        String skipReason = skipReason(run, svc, blocked);
                        if (skipReason != null) {
                            blocked.add(svc.getName());
                            run.service(svc.getName()).skipped(skipReason);
                            run.publish(EventType.SERVICE_SKIPPED, svc.getName(), null, skipReason);
                            continue;
                        }
                        futures.add(pool.submit(() -> {
                            permits.acquireUninterruptibly();
                            try {
                                runService(run, svc, blocked);
                            } finally {
                                permits.release();
                            }
                        }));
                    }
                    for (Future<?> f : futures) {
                        try {
                            f.get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            run.cancel();
                        } catch (ExecutionException e) {
                            run.publish(EventType.LOG, null, null, "Unexpected error: " + e.getCause());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            run.publish(EventType.LOG, null, null, "Run aborted: " + e);
        } finally {
            try {
                run.finish();
                RunView v = run.view();
                run.publish(EventType.RUN_FINISHED, null, null, v.succeeded() + " succeeded, " + v.failed()
                        + " failed, " + v.skipped() + " skipped");
                writeReport(run, v);
            } finally {
                run.close(); // only now may a new run start or a waiter proceed
            }
        }
    }

    private String skipReason(Run run, ServiceDef svc, Set<String> blocked) {
        if (run.cancelRequested()) {
            return "Run was cancelled";
        }
        for (String dep : svc.getDependsOn()) {
            if (blocked.contains(dep)) {
                return "Skipped because dependency '" + dep + "' did not migrate";
            }
        }
        return null;
    }

    private void runService(Run run, ServiceDef svc, Set<String> blocked) {
        ServiceRun sr = run.service(svc.getName());
        MigrationState state = new MigrationState(run.id(), svc, cfg.getMaxRepairAttempts());
        state.onNote(msg -> run.publish(EventType.LOG, svc.getName(), null, msg));
        sr.started();
        run.publish(EventType.SERVICE_STARTED, svc.getName(), null, "Agent started");

        try {
            graph.invoke(state, new StateGraph.Listener<>() {
                @Override
                public void onNodeStart(String node, MigrationState s) {
                    sr.node(node);
                    sr.progress(s);
                    run.publish(EventType.NODE_STARTED, svc.getName(), node, null);
                }

                @Override
                public void onNodeEnd(String node, MigrationState s, Duration took) {
                    sr.progress(s);
                    run.publish(EventType.NODE_FINISHED, svc.getName(), node, took.toMillis() + " ms");
                }

                @Override
                public void onNodeError(String node, MigrationState s, Throwable error) {
                    run.publish(EventType.NODE_FAILED, svc.getName(), node, String.valueOf(error));
                }
            });
        } catch (RuntimeException e) {
            state.status = ServiceStatus.FAILED;
            state.failureReason = e.getMessage();
        }

        sr.finished(state);
        if (state.status == ServiceStatus.SUCCEEDED) {
            run.publish(EventType.SERVICE_SUCCEEDED, svc.getName(), null,
                    state.prUrl != null ? state.prUrl : "migrated");
        } else {
            blocked.add(svc.getName());
            run.publish(EventType.SERVICE_FAILED, svc.getName(), null, firstLine(state.failureReason));
        }
    }

    private void writeReport(Run run, RunView view) {
        try {
            Path dir = cfg.workRootPath().resolve(run.id());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.md"), MarkdownReport.render(view));
        } catch (IOException e) {
            run.publish(EventType.LOG, null, null, "Could not write report: " + e.getMessage());
        }
    }

    private static String names(List<ServiceDef> wave) {
        return wave.stream().map(ServiceDef::getName).toList().toString();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "failed";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
