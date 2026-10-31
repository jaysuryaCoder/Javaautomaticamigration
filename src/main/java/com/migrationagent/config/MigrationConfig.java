package com.migrationagent.config;

import com.migrationagent.infra.AnthropicLlmClient;
import com.migrationagent.infra.CliGitOps;
import com.migrationagent.infra.OpenRewriteRunner;
import com.migrationagent.infra.ShellBuildRunner;
import com.migrationagent.model.MigrationSettings;
import com.migrationagent.orchestrator.MigrationOrchestrator;
import com.migrationagent.orchestrator.RunRegistry;
import com.migrationagent.sim.SimulatedTools;
import com.migrationagent.tools.ThrottledLlmClient;
import com.migrationagent.tools.Toolbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class MigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(MigrationConfig.class);

    /** Bound from the {@code migration.*} properties (application.yml + services.yml). */
    @Bean
    @ConfigurationProperties(prefix = "migration")
    MigrationSettings migrationSettings() {
        return new MigrationSettings();
    }

    @Bean
    Toolbox toolbox(MigrationSettings s) {
        if (s.getMode() == MigrationSettings.Mode.SIMULATE) {
            log.warn("Running in SIMULATE mode: nothing is cloned, built or pushed. Set MIGRATION_MODE=REAL for real runs.");
            return new SimulatedTools(s.getSimulationSpeed()).toolbox();
        }
        if (s.getLlm().getApiKey() == null || s.getLlm().getApiKey().isBlank()) {
            throw new IllegalStateException("REAL mode needs an LLM key: set ANTHROPIC_API_KEY (migration.llm.api-key)");
        }
        verifyJdk(s);
        return new Toolbox(
                new CliGitOps(s),
                new OpenRewriteRunner(s),
                new ShellBuildRunner(s),
                new ThrottledLlmClient(new AnthropicLlmClient(s.getLlm()), s.getLlm().getConcurrency()));
    }

    @Bean
    RunRegistry runRegistry() {
        return new RunRegistry();
    }

    @Bean
    MigrationOrchestrator migrationOrchestrator(Toolbox toolbox, MigrationSettings settings, RunRegistry registry) {
        return new MigrationOrchestrator(toolbox, settings, registry);
    }

    /** A "migration" verified on the wrong JDK is worthless, so refuse to start if JDK 25 is not what we'll build with. */
    private static void verifyJdk(MigrationSettings s) {
        String home = s.getJdkHome();
        if (home == null || home.isBlank() || !Files.isDirectory(Path.of(home, "bin"))) {
            throw new IllegalStateException("REAL mode needs migration.jdk-home (JDK_HOME of a JDK "
                    + s.getTargetJava() + ") but got '" + home + "'");
        }
        Path release = Path.of(home, "release");
        try {
            if (Files.exists(release) && !Files.readString(release).contains("JAVA_VERSION=\"" + s.getTargetJava())) {
                throw new IllegalStateException("migration.jdk-home (" + home + ") is not a JDK " + s.getTargetJava());
            }
        } catch (IOException e) {
            log.warn("Could not read {}; skipping JDK version check", release);
        }
    }
}
