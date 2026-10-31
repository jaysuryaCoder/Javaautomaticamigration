package com.migrationagent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** One microservice to migrate. Mutable bean so Spring can bind it from services.yml. */
public class ServiceDef {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern REPO = Pattern.compile("(https://|ssh://|git@|file:///|/)\\S+");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,100}");

    private String name;
    private String repoUrl;
    private String branch = "main";
    /** AUTO, MAVEN or GRADLE. */
    private String buildTool = "AUTO";
    /** Names of services (e.g. shared libraries / BOMs) that must be migrated first. */
    private List<String> dependsOn = new ArrayList<>();
    /** Sub-directory of the repo that holds the service (monorepos). */
    private String subDir = "";

    public ServiceDef() {}

    public ServiceDef(String name, String repoUrl, String... dependsOn) {
        this.name = name;
        this.repoUrl = repoUrl;
        this.dependsOn = new ArrayList<>(List.of(dependsOn));
    }

    /** Names and URLs end up in file paths and git command lines, so they are validated strictly. */
    public void validate() {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid service name: " + name);
        }
        if (repoUrl == null || !REPO.matcher(repoUrl).matches()) {
            throw new IllegalArgumentException("Invalid repoUrl for " + name + ": " + repoUrl);
        }
        if (branch == null || !BRANCH.matcher(branch).matches() || branch.contains("..")) {
            throw new IllegalArgumentException("Invalid branch for " + name + ": " + branch);
        }
        String tool = buildTool == null ? "" : buildTool.toUpperCase();
        if (!List.of("AUTO", "MAVEN", "GRADLE").contains(tool)) {
            throw new IllegalArgumentException("Invalid buildTool for " + name + ": " + buildTool);
        }
        if (subDir != null && (subDir.contains("..") || subDir.startsWith("/") || subDir.startsWith("-"))) {
            throw new IllegalArgumentException("Invalid subDir for " + name + ": " + subDir);
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getBuildTool() { return buildTool; }
    public void setBuildTool(String buildTool) { this.buildTool = buildTool; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn == null ? new ArrayList<>() : dependsOn; }
    public String getSubDir() { return subDir; }
    public void setSubDir(String subDir) { this.subDir = subDir == null ? "" : subDir; }

    @Override
    public String toString() {
        return name;
    }
}
