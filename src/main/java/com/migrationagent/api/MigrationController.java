package com.migrationagent.api;

import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.orchestrator.DependencyPlanner;
import com.migrationagent.orchestrator.EventType;
import com.migrationagent.orchestrator.MigrationOrchestrator;
import com.migrationagent.orchestrator.Run;
import com.migrationagent.orchestrator.RunRegistry;
import com.migrationagent.orchestrator.RunView;
import com.migrationagent.report.MarkdownReport;
import com.migrationagent.sim.SimulatedTools;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api")
public class MigrationController {

    /** Optional body: {"services": ["orders-service", "common-lib"]}. Omit to migrate everything configured. */
    public record StartRequest(List<String> services) {}

    private final MigrationOrchestrator orchestrator;
    private final RunRegistry registry;
    private final MigrationSettings settings;

    public MigrationController(MigrationOrchestrator orchestrator, RunRegistry registry, MigrationSettings settings) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.settings = settings;
    }

    @GetMapping("/services")
    public Map<String, Object> services() {
        List<ServiceDef> all = configuredServices();
        try {
            List<List<String>> waves = DependencyPlanner.plan(all).stream()
                    .map(w -> w.stream().map(ServiceDef::getName).toList()).toList();
            return Map.of("mode", settings.getMode(), "count", all.size(), "waves", waves);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/migrations")
    public ResponseEntity<RunView> start(@RequestBody(required = false) StartRequest request) {
        List<ServiceDef> selected = select(request);
        try {
            Run run = orchestrator.start(selected);
            return ResponseEntity.accepted().body(run.view());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/migrations")
    public List<RunView> list() {
        return registry.list();
    }

    @GetMapping("/migrations/{id}")
    public RunView get(@PathVariable String id) {
        return find(id).view();
    }

    @GetMapping(value = "/migrations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        Run run = find(id);
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout; the stream ends with RUN_FINISHED
        AtomicReference<Runnable> unsubscribe = new AtomicReference<>(() -> { });
        emitter.onCompletion(() -> unsubscribe.get().run());
        emitter.onTimeout(() -> unsubscribe.get().run());
        emitter.onError(e -> unsubscribe.get().run());
        unsubscribe.set(run.subscribe(event -> {
            try {
                emitter.send(SseEmitter.event().name("run-event").data(event, MediaType.APPLICATION_JSON));
                if (event.type() == EventType.RUN_FINISHED) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e); // client went away
            }
        }));
        return emitter;
    }

    @GetMapping(value = "/migrations/{id}/report", produces = "text/markdown;charset=UTF-8")
    public String report(@PathVariable String id) {
        return MarkdownReport.render(find(id).view());
    }

    /** Stops scheduling new services; builds already running finish on their own. */
    @DeleteMapping("/migrations/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        find(id).cancel();
        return ResponseEntity.accepted().build();
    }

    private Run find(String id) {
        return registry.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run " + id));
    }

    private List<ServiceDef> configuredServices() {
        if (settings.getServices().isEmpty() && settings.getMode() == MigrationSettings.Mode.SIMULATE) {
            return SimulatedTools.demoServices();
        }
        return settings.getServices();
    }

    private List<ServiceDef> select(StartRequest request) {
        List<ServiceDef> all = configuredServices();
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No services configured. Copy services.example.yml to services.yml and edit it.");
        }
        if (request == null || request.services() == null || request.services().isEmpty()) {
            return all;
        }
        Set<String> wanted = new LinkedHashSet<>(request.services());
        List<ServiceDef> selected = all.stream().filter(s -> wanted.contains(s.getName())).toList();
        if (selected.size() != wanted.size()) {
            Set<String> unknown = new LinkedHashSet<>(wanted);
            selected.forEach(s -> unknown.remove(s.getName()));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown services: " + unknown);
        }
        return selected;
    }
}
