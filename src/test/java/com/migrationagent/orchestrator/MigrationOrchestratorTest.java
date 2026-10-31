package com.migrationagent.orchestrator;

import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.model.ServiceStatus;
import com.migrationagent.sim.SimulatedTools;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real graph, prompt builder and patch applier with the simulated toolbox (no network, no Maven). */
class MigrationOrchestratorTest {

    private static MigrationSettings settings() throws IOException {
        MigrationSettings cfg = new MigrationSettings();
        cfg.setWorkRoot(Files.createTempDirectory("orchestrator-test").toString());
        cfg.setSimulationSpeed(0);
        cfg.setMaxParallel(8);
        cfg.setMaxRepairAttempts(3);
        return cfg;
    }

    private static ServiceView view(RunView run, String name) {
        return run.services().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void migratesThirtyServicesRepairsWhatItCanAndSkipsDependentsOfFailures() throws Exception {
        MigrationSettings cfg = settings();
        var orchestrator = new MigrationOrchestrator(new SimulatedTools(0).toolbox(), cfg, new RunRegistry());

        Run run = orchestrator.startAndWait(SimulatedTools.demoServices());
        RunView v = run.view();

        assertEquals(30, v.total());
        assertEquals(28, v.succeeded());
        assertEquals(1, v.failed());
        assertEquals(1, v.skipped());
        assertEquals(Run.Status.COMPLETED_WITH_FAILURES, v.status());

        // the hard one: the LLM said it needs a human, so no further repair rounds were burned
        ServiceView billing = view(v, "legacy-billing-engine");
        assertEquals(ServiceStatus.FAILED, billing.status());
        assertTrue(billing.reason().contains("needs a human") || billing.reason().contains("Agent needs a human"));
        assertEquals(1, billing.repairAttempts());

        // its dependent was never built against a broken parent
        ServiceView reporting = view(v, "reporting-service");
        assertEquals(ServiceStatus.SKIPPED, reporting.status());
        assertTrue(reporting.reason().contains("legacy-billing-engine"));

        // the LLM repair loop genuinely fixed some services and opened PRs for them
        List<ServiceView> repaired = v.services().stream()
                .filter(s -> s.status() == ServiceStatus.SUCCEEDED && s.repairAttempts() == 1).toList();
        assertTrue(!repaired.isEmpty(), "expected at least one service fixed by the repair loop");
        repaired.forEach(s -> assertNotNull(s.prUrl()));

        // a markdown report was written next to the checkouts
        Path report = cfg.workRootPath().resolve(run.id()).resolve("report.md");
        assertTrue(Files.readString(report).contains("legacy-billing-engine"));
    }

    @Test
    void sharedLibrariesFinishBeforeDependentsStart() throws Exception {
        var orchestrator = new MigrationOrchestrator(new SimulatedTools(0).toolbox(), settings(), new RunRegistry());
        Run run = orchestrator.startAndWait(SimulatedTools.demoServices());

        List<RunEvent> events = new ArrayList<>();
        run.subscribe(events::add);

        int bomDone = indexOf(events, EventType.SERVICE_SUCCEEDED, "platform-bom");
        int libStart = indexOf(events, EventType.SERVICE_STARTED, "common-lib");
        int libDone = indexOf(events, EventType.SERVICE_SUCCEEDED, "common-lib");
        int firstServiceStart = indexOf(events, EventType.SERVICE_STARTED, "auth-service");

        assertTrue(bomDone >= 0 && bomDone < libStart, "bom must finish before common-lib starts");
        assertTrue(libDone >= 0 && libDone < firstServiceStart, "common-lib must finish before services start");
        assertEquals(EventType.RUN_FINISHED, events.get(events.size() - 1).type());
    }

    @Test
    void onlyOneRunMayBeActiveAndBadInputIsRejected() throws Exception {
        MigrationSettings cfg = settings();
        cfg.setSimulationSpeed(0.2); // slow enough that the first run is still going
        var orchestrator = new MigrationOrchestrator(new SimulatedTools(0.2).toolbox(), cfg, new RunRegistry());

        Run first = orchestrator.start(SimulatedTools.demoServices());
        assertThrows(IllegalStateException.class, () -> orchestrator.start(SimulatedTools.demoServices()));
        first.cancel();
        while (!first.isFinished()) {
            Thread.sleep(25);
        }
        assertEquals(Run.Status.CANCELLED, first.status());

        assertThrows(IllegalArgumentException.class, () -> orchestrator.start(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.start(List.of(new ServiceDef("../evil", "https://example.com/x.git"))));
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.start(List.of(new ServiceDef("ok", "--upload-pack=touch /tmp/pwned"))));
    }

    private static int indexOf(List<RunEvent> events, EventType type, String service) {
        for (int i = 0; i < events.size(); i++) {
            RunEvent e = events.get(i);
            if (e.type() == type && service.equals(e.service())) {
                return i;
            }
        }
        return -1;
    }
}
