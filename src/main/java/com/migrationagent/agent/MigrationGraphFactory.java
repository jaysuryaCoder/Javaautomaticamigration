package com.migrationagent.agent;

import com.migrationagent.graph.StateGraph;
import com.migrationagent.model.BuildResult;
import com.migrationagent.model.MigrationSettings;
import com.migrationagent.model.RewriteResult;
import com.migrationagent.model.ServiceStatus;
import com.migrationagent.tools.Toolbox;

import java.nio.file.Path;
import java.util.Map;

import static com.migrationagent.graph.StateGraph.END;
import static com.migrationagent.graph.StateGraph.START;

/**
 * The per-service agent, expressed as a graph:
 *
 * <pre>
 * prepare -> analyze -> rewrite -> build --ok--> publish -> END
 *                                    ^  \--retry--> repair --rebuild--+
 *                                    +--------------------------------+
 *                                    \--giveup--> fail -> END
 * </pre>
 */
public final class MigrationGraphFactory {
    private MigrationGraphFactory() {}

    public static StateGraph<MigrationState> definition(Toolbox t, MigrationSettings cfg) {
        return new StateGraph<MigrationState>()
                .addNode("prepare", s -> prepare(s, t, cfg))
                .addNode("analyze", s -> analyze(s))
                .addNode("rewrite", s -> rewrite(s, t, cfg))
                .addNode("build", s -> build(s, t, cfg))
                .addNode("repair", s -> repair(s, t, cfg))
                .addNode("publish", s -> publish(s, t, cfg))
                .addNode("fail", MigrationGraphFactory::fail)
                .addEdge(START, "prepare")
                .addEdge("prepare", "analyze")
                .addEdge("analyze", "rewrite")
                .addEdge("rewrite", "build")
                .addConditionalEdges("build", MigrationGraphFactory::afterBuild,
                        Map.of("ok", "publish", "retry", "repair", "giveup", "fail"))
                .addConditionalEdges("repair", MigrationGraphFactory::afterRepair,
                        Map.of("rebuild", "build", "giveup", "fail"))
                .addEdge("publish", END)
                .addEdge("fail", END);
    }

    public static StateGraph.CompiledGraph<MigrationState> create(Toolbox t, MigrationSettings cfg) {
        return definition(t, cfg).compile();
    }

    // ---- nodes -----------------------------------------------------------------------------------------------

    private static void prepare(MigrationState s, Toolbox t, MigrationSettings cfg) throws Exception {
        Path runRoot = cfg.workRootPath().resolve(s.runId);
        s.branch = cfg.getBranchName();
        s.projectDir = t.git().checkout(s.service, runRoot, s.branch);
        s.note("Checked out " + s.service.getBranch() + " onto branch " + s.branch);
    }

    private static void analyze(MigrationState s) throws Exception {
        s.project = ProjectScanner.scan(s.projectDir, s.service);
        s.note("Detected " + s.project.buildTool() + ", Java " + nullToUnknown(s.project.javaVersion())
                + ", Spring Boot " + nullToUnknown(s.project.springBootVersion()));
        s.project.warnings().forEach(w -> s.note("Heads-up: " + w));
    }

    private static void rewrite(MigrationState s, Toolbox t, MigrationSettings cfg) throws Exception {
        if (!cfg.isUseOpenRewrite()) {
            s.note("OpenRewrite disabled, skipping deterministic pass");
            return;
        }
        RewriteResult r = t.rewrite().run(s.projectDir, s.service);
        s.note(r.success()
                ? "OpenRewrite applied " + cfg.getRewriteRecipe() + " in " + secs(r.millis())
                : "OpenRewrite failed (continuing, the repair loop will see the build errors)");
    }

    private static void build(MigrationState s, Toolbox t, MigrationSettings cfg) throws Exception {
        s.lastBuild = t.build().build(s.projectDir, s.service);
        s.note("Build on JDK " + cfg.getTargetJava() + (s.lastBuild.success() ? " passed" : " FAILED")
                + " in " + secs(s.lastBuild.millis()) + (s.lastBuild.timedOut() ? " (timed out)" : ""));
    }

    private static void repair(MigrationState s, Toolbox t, MigrationSettings cfg) throws Exception {
        s.repairAttempts++;
        String prompt = RepairPromptBuilder.build(s, cfg.getMaxContextChars());
        String reply = t.llm().complete(MigrationPlaybook.SYSTEM_PROMPT, prompt);
        s.llmCalls++;

        PatchApplier.Result r = PatchApplier.apply(s.projectDir, reply);
        s.changedFiles.addAll(r.files());
        s.lastRejections = r.rejected();

        if (r.giveUpReason() != null && r.applied().isEmpty()) {
            s.giveUp = true;
            s.failureReason = "Agent needs a human: " + r.giveUpReason();
            s.note("LLM gave up: " + r.giveUpReason());
            return;
        }
        if (r.applied().isEmpty()) {
            s.fruitlessRepairs++;
        } else {
            s.fruitlessRepairs = 0;
        }
        s.note("Repair " + s.repairAttempts + "/" + s.maxRepairAttempts + ": applied " + r.applied().size()
                + " edit(s), rejected " + r.rejected().size());
    }

    private static void publish(MigrationState s, Toolbox t, MigrationSettings cfg) throws Exception {
        boolean committed = t.git().commitAll(s.projectDir, cfg.getCommitMessage());
        if (!committed) {
            s.note("No file changes were needed, service is already Java " + cfg.getTargetJava() + " compatible");
        } else if (!cfg.isPush()) {
            s.note("Committed locally on " + s.branch + " (push disabled)");
        } else {
            t.git().push(s.projectDir, s.branch);
            s.note("Pushed " + s.branch);
            if (cfg.isOpenPullRequest()) {
                s.prUrl = t.git().openPullRequest(s.projectDir, s.service, s.branch,
                        "Migrate " + s.service.getName() + " to Java " + cfg.getTargetJava(), prBody(s, cfg));
                s.note(s.prUrl != null ? "Opened PR " + s.prUrl : "Could not open a PR automatically; open one from " + s.branch);
            }
        }
        s.status = ServiceStatus.SUCCEEDED;
    }

    private static void fail(MigrationState s) {
        s.status = ServiceStatus.FAILED;
        if (s.failureReason == null) {
            BuildResult b = s.lastBuild;
            s.failureReason = b == null ? "unknown failure"
                    : (b.timedOut() ? "Build timed out. " : "Build still failing after " + s.repairAttempts + " repair attempt(s). ")
                    + lastLines(b.tail(800), 8);
        }
        s.note("Giving up: " + firstLine(s.failureReason));
    }

    // ---- routing ---------------------------------------------------------------------------------------------

    private static String afterBuild(MigrationState s) {
        if (s.lastBuild.success()) {
            return "ok";
        }
        return s.repairAttempts < s.maxRepairAttempts && !s.giveUp ? "retry" : "giveup";
    }

    private static String afterRepair(MigrationState s) {
        return s.giveUp || s.fruitlessRepairs >= 2 ? "giveup" : "rebuild";
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static String prBody(MigrationState s, MigrationSettings cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("Automated migration to Java ").append(cfg.getTargetJava()).append(" by the migration agent.\n\n");
        sb.append("- Deterministic pass: OpenRewrite `").append(cfg.getRewriteRecipe()).append("`\n");
        sb.append("- LLM repair rounds used: ").append(s.repairAttempts).append('\n');
        sb.append("- Build and tests verified on JDK ").append(cfg.getTargetJava()).append('\n');
        if (!s.changedFiles.isEmpty()) {
            sb.append("- Files edited by the LLM (review these first):\n");
            s.changedFiles.forEach(f -> sb.append("  - `").append(f).append("`\n"));
        }
        sb.append("\nPlease review before merging; nothing was merged automatically.\n");
        return sb.toString();
    }

    private static String nullToUnknown(String v) {
        return v == null ? "unknown" : v;
    }

    private static String secs(long millis) {
        return String.format("%.1fs", millis / 1000.0);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.split("\\R");
        int from = Math.max(0, lines.length - n);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
