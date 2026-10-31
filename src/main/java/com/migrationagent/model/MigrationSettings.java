package com.migrationagent.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * All tunables. A plain JavaBean (no framework annotations) so the core stays dependency-free;
 * Spring binds it from the {@code migration.*} properties in application.yml / services.yml.
 */
public class MigrationSettings {

    public enum Mode { REAL, SIMULATE }

    public static class Llm {
        private String apiKey = "";
        private String baseUrl = "https://api.anthropic.com";
        private String model = "claude-sonnet-5-5";
        private int maxTokens = 8000;
        /** Max simultaneous LLM calls across all service agents (rate-limit protection). */
        private int concurrency = 8;
        private int timeoutSeconds = 120;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    private Mode mode = Mode.SIMULATE;
    private String workRoot = "./work";
    /** Home of the JDK used to BUILD the migrated services (must be JDK 25). */
    private String jdkHome = "";
    private String targetJava = "25";
    private int maxParallel = 15;
    private int maxRepairAttempts = 3;
    private int buildTimeoutMinutes = 8;
    private int rewriteTimeoutMinutes = 5;
    private boolean skipTests = false;
    private boolean useOpenRewrite = true;
    private String rewriteRecipe = "org.openrewrite.java.migrate.UpgradeToJava25";
    private String rewritePluginVersion = "6.53.0";
    private String rewriteRecipeCoordinates = "org.openrewrite.recipe:rewrite-migrate-java:RELEASE";
    private String branchName = "migrate/java-25";
    private String commitMessage;
    private boolean push = true;
    private boolean openPullRequest = true;
    private String gitUserName = "migration-agent";
    private String gitUserEmail = "migration-agent@example.com";
    private String mavenOpts = "-Xmx1g";
    private int maxContextChars = 60_000;
    /** SIMULATE mode only: 1.0 = a few seconds per service, 0 = no delays (tests). */
    private double simulationSpeed = 1.0;
    /** If set, every /api call must carry it as X-API-Token header or ?token= parameter. */
    private String apiToken = "";
    private List<ServiceDef> services = new ArrayList<>();
    private final Llm llm = new Llm();

    public Path workRootPath() {
        return Path.of(workRoot).toAbsolutePath().normalize();
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getWorkRoot() { return workRoot; }
    public void setWorkRoot(String workRoot) { this.workRoot = workRoot; }
    public String getJdkHome() { return jdkHome; }
    public void setJdkHome(String jdkHome) { this.jdkHome = jdkHome; }
    public String getTargetJava() { return targetJava; }
    public void setTargetJava(String targetJava) { this.targetJava = targetJava; }
    public int getMaxParallel() { return maxParallel; }
    public void setMaxParallel(int maxParallel) { this.maxParallel = Math.max(1, maxParallel); }
    public int getMaxRepairAttempts() { return maxRepairAttempts; }
    public void setMaxRepairAttempts(int maxRepairAttempts) { this.maxRepairAttempts = Math.max(0, maxRepairAttempts); }
    public int getBuildTimeoutMinutes() { return buildTimeoutMinutes; }
    public void setBuildTimeoutMinutes(int buildTimeoutMinutes) { this.buildTimeoutMinutes = buildTimeoutMinutes; }
    public int getRewriteTimeoutMinutes() { return rewriteTimeoutMinutes; }
    public void setRewriteTimeoutMinutes(int rewriteTimeoutMinutes) { this.rewriteTimeoutMinutes = rewriteTimeoutMinutes; }
    public boolean isSkipTests() { return skipTests; }
    public void setSkipTests(boolean skipTests) { this.skipTests = skipTests; }
    public boolean isUseOpenRewrite() { return useOpenRewrite; }
    public void setUseOpenRewrite(boolean useOpenRewrite) { this.useOpenRewrite = useOpenRewrite; }
    public String getRewriteRecipe() { return rewriteRecipe; }
    public void setRewriteRecipe(String rewriteRecipe) { this.rewriteRecipe = rewriteRecipe; }
    public String getRewritePluginVersion() { return rewritePluginVersion; }
    public void setRewritePluginVersion(String v) { this.rewritePluginVersion = v; }
    public String getRewriteRecipeCoordinates() { return rewriteRecipeCoordinates; }
    public void setRewriteRecipeCoordinates(String v) { this.rewriteRecipeCoordinates = v; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getCommitMessage() {
        return commitMessage != null ? commitMessage : "chore: migrate to Java " + targetJava + " (automated)";
    }
    public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }
    public boolean isPush() { return push; }
    public void setPush(boolean push) { this.push = push; }
    public boolean isOpenPullRequest() { return openPullRequest; }
    public void setOpenPullRequest(boolean openPullRequest) { this.openPullRequest = openPullRequest; }
    public String getGitUserName() { return gitUserName; }
    public void setGitUserName(String gitUserName) { this.gitUserName = gitUserName; }
    public String getGitUserEmail() { return gitUserEmail; }
    public void setGitUserEmail(String gitUserEmail) { this.gitUserEmail = gitUserEmail; }
    public String getMavenOpts() { return mavenOpts; }
    public void setMavenOpts(String mavenOpts) { this.mavenOpts = mavenOpts; }
    public int getMaxContextChars() { return maxContextChars; }
    public void setMaxContextChars(int maxContextChars) { this.maxContextChars = maxContextChars; }
    public double getSimulationSpeed() { return simulationSpeed; }
    public void setSimulationSpeed(double simulationSpeed) { this.simulationSpeed = simulationSpeed; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public List<ServiceDef> getServices() { return services; }
    public void setServices(List<ServiceDef> services) { this.services = services == null ? new ArrayList<>() : services; }
    public Llm getLlm() { return llm; }
}
