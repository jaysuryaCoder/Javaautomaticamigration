package com.migrationagent.orchestrator;

import com.migrationagent.model.ServiceDef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups services into waves: everything in a wave can run in parallel, and a wave only starts once the
 * services it depends on (shared libraries, BOMs, parents) have finished. Dependencies on services that are
 * not part of this run are ignored.
 */
public final class DependencyPlanner {
    private DependencyPlanner() {}

    public static List<List<ServiceDef>> plan(List<ServiceDef> services) {
        Map<String, ServiceDef> byName = new LinkedHashMap<>();
        for (ServiceDef s : services) {
            if (byName.putIfAbsent(s.getName(), s) != null) {
                throw new IllegalArgumentException("Duplicate service name: " + s.getName());
            }
        }
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (ServiceDef s : services) {
            inDegree.putIfAbsent(s.getName(), 0);
            for (String dep : s.getDependsOn()) {
                if (byName.containsKey(dep)) {
                    inDegree.merge(s.getName(), 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.getName());
                }
            }
        }

        List<List<ServiceDef>> waves = new ArrayList<>();
        List<String> ready = new ArrayList<>();
        inDegree.forEach((name, deg) -> {
            if (deg == 0) {
                ready.add(name);
            }
        });
        int placed = 0;
        while (!ready.isEmpty()) {
            List<ServiceDef> wave = new ArrayList<>();
            List<String> next = new ArrayList<>();
            for (String name : ready) {
                wave.add(byName.get(name));
                placed++;
                for (String child : dependents.getOrDefault(name, List.of())) {
                    if (inDegree.merge(child, -1, Integer::sum) == 0) {
                        next.add(child);
                    }
                }
            }
            waves.add(wave);
            ready.clear();
            ready.addAll(next);
        }
        if (placed != byName.size()) {
            List<String> stuck = new ArrayList<>();
            inDegree.forEach((n, d) -> {
                if (d > 0) {
                    stuck.add(n);
                }
            });
            throw new IllegalArgumentException("Dependency cycle among services: " + stuck);
        }
        return waves;
    }
}
