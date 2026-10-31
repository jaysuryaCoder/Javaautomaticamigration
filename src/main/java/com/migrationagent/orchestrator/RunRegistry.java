package com.migrationagent.orchestrator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RunRegistry {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    void add(Run run) {
        runs.put(run.id(), run);
    }

    public Optional<Run> get(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    public Optional<Run> active() {
        return runs.values().stream().filter(r -> !r.isFinished()).findFirst();
    }

    public List<RunView> list() {
        return runs.values().stream().map(Run::view)
                .sorted(Comparator.comparing(RunView::startedAt).reversed()).toList();
    }
}
