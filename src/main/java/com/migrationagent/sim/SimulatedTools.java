package com.migrationagent.sim;

import com.migrationagent.model.BuildResult;
import com.migrationagent.model.RewriteResult;
import com.migrationagent.model.ServiceDef;
import com.migrationagent.tools.BuildRunner;
import com.migrationagent.tools.GitOps;
import com.migrationagent.tools.LlmClient;
import com.migrationagent.tools.RewriteRunner;
import com.migrationagent.tools.Toolbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A fully fake toolbox: real graph, real prompt building, real patch application, but no network, git or
 * Maven. It lets you watch 30 agents run on the dashboard in seconds, and it backs the test-suite.
 *
 * <p>Every demo service gets a tiny source file. Some contain {@code legacyApi()} (the "LLM" can fix that),
 * and {@code legacy-billing-engine} contains {@code removedApi()} (needs a human).
 */
public final class SimulatedTools {

    static final String SOURCE = "src/main/java/com/example/Application.java";

    private final double speed;

    public SimulatedTools(double speed) {
        this.speed = speed;
    }

    public Toolbox toolbox() {
        return new Toolbox(new Git(), new Rewrite(), new Build(), new Llm());
    }

    public static List<ServiceDef> demoServices() {
        String[] names = {"auth", "users", "orders", "payments", "inventory", "catalog", "cart", "checkout",
                "shipping", "notifications", "search", "reviews", "recommendations", "pricing", "promotions",
                "tax", "fraud", "ledger", "email", "sms", "audit", "gateway", "profile", "loyalty", "returns",
                "analytics"};
        List<ServiceDef> out = new ArrayList<>();
        out.add(new ServiceDef("platform-bom", "https://github.com/demo-org/platform-bom.git"));
        out.add(new ServiceDef("common-lib", "https://github.com/demo-org/common-lib.git", "platform-bom"));
        for (String n : names) {
            out.add(new ServiceDef(n + "-service", "https://github.com/demo-org/" + n + "-service.git", "common-lib"));
        }
        out.add(new ServiceDef("legacy-billing-engine", "https://github.com/demo-org/legacy-billing-engine.git", "common-lib"));
        out.add(new ServiceDef("reporting-service", "https://github.com/demo-org/reporting-service.git",
                "common-lib", "legacy-billing-engine"));
        return out;
    }

    private void pause(long baseMillis, String seed) {
        if (speed <= 0) {
            return;
        }
        double jitter = 0.6 + (Math.floorMod(seed.hashCode(), 80) / 100.0);
        try {
            Thread.sleep((long) (baseMillis * speed * jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Git implements GitOps {
        @Override
        public Path checkout(ServiceDef svc, Path workRoot, String branch) throws IOException {
            pause(600, svc.getName());
            Path dir = workRoot.resolve(svc.getName());
            Path src = dir.resolve(SOURCE);
            Files.createDirectories(src.getParent());
            Files.writeString(dir.resolve("pom.xml"), """
                    <project>
                      <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.4</version>
                      </parent>
                      <artifactId>%s</artifactId>
                      <properties><java.version>17</java.version></properties>
                    </project>
                    """.formatted(svc.getName()));
            String call = svc.getName().equals("legacy-billing-engine") ? "removedApi()"
                    : Math.floorMod(svc.getName().hashCode(), 4) == 0 ? "legacyApi()" : "modernApi()";
            Files.writeString(src, """
                    package com.example;

                    public class Application {
                        void run() {
                            %s;
                        }
                    }
                    """.formatted(call));
            return dir;
        }

        @Override
        public boolean commitAll(Path projectDir, String message) {
            return true;
        }

        @Override
        public void push(Path projectDir, String branch) {
            // nothing to push in simulation
        }

        @Override
        public String openPullRequest(Path projectDir, ServiceDef svc, String branch, String title, String body) {
            return "https://github.com/demo-org/" + svc.getName() + "/pull/1";
        }
    }

    private final class Rewrite implements RewriteRunner {
        @Override
        public RewriteResult run(Path dir, ServiceDef svc) throws IOException {
            pause(900, svc.getName() + "r");
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("<java.version>17<", "<java.version>25<"));
            return new RewriteResult(true, "simulated", 900);
        }
    }

    private final class Build implements BuildRunner {
        @Override
        public BuildResult build(Path dir, ServiceDef svc) throws IOException {
            pause(1500, svc.getName() + "b");
            Path src = dir.resolve(SOURCE).toAbsolutePath();
            String code = Files.readString(src);
            for (String bad : new String[]{"legacyApi()", "removedApi()"}) {
                if (code.contains(bad)) {
                    String log = "[INFO] Building " + svc.getName() + "\n"
                            + "[ERROR] COMPILATION ERROR :\n"
                            + "[ERROR] " + src + ":[5,9] cannot find symbol\n"
                            + "[ERROR]   symbol:   method " + bad + "\n"
                            + "[INFO] BUILD FAILURE\n";
                    return new BuildResult(false, 1, false, log, 1500);
                }
            }
            return new BuildResult(true, 0, false, "[INFO] BUILD SUCCESS", 1500);
        }
    }

    private final class Llm implements LlmClient {
        @Override
        public String complete(String systemPrompt, String userPrompt) {
            pause(1200, userPrompt.substring(0, Math.min(40, userPrompt.length())));
            if (userPrompt.contains("removedApi()")) {
                return "NO_CHANGES_POSSIBLE: removedApi() belongs to a vendor SDK that needs a major upgrade";
            }
            if (userPrompt.contains("legacyApi()")) {
                return "FILE: " + SOURCE + "\n<<<<<<< SEARCH\nlegacyApi();\n=======\n"
                        + "modernApi();\n>>>>>>> REPLACE\n";
            }
            return "NO_CHANGES_POSSIBLE: no context to work from";
        }
    }
}
