package com.migrationagent.orchestrator;

import com.migrationagent.model.ServiceDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyPlannerTest {

    private static ServiceDef svc(String name, String... deps) {
        return new ServiceDef(name, "https://example.com/" + name + ".git", deps);
    }

    private static List<String> names(List<ServiceDef> wave) {
        return wave.stream().map(ServiceDef::getName).toList();
    }

    @Test
    void sharedLibrariesComeFirstAndIndependentServicesRunTogether() {
        var waves = DependencyPlanner.plan(List.of(
                svc("orders", "common"), svc("users", "common"), svc("common", "bom"), svc("bom")));

        assertEquals(3, waves.size());
        assertEquals(List.of("bom"), names(waves.get(0)));
        assertEquals(List.of("common"), names(waves.get(1)));
        assertEquals(List.of("orders", "users"), names(waves.get(2)));
    }

    @Test
    void dependenciesOutsideTheRunAreIgnored() {
        var waves = DependencyPlanner.plan(List.of(svc("orders", "common-not-in-this-run")));
        assertEquals(1, waves.size());
    }

    @Test
    void cyclesAreReported() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> DependencyPlanner.plan(List.of(svc("a", "b"), svc("b", "a"))));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void duplicateNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DependencyPlanner.plan(List.of(svc("a"), svc("a"))));
    }
}
