package vip.mate.loop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.loop.model.SuperpowerDefinition;
import vip.mate.skill.manifest.SkillManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LoopCommandExecutor {

    private static final int COMMAND_TIMEOUT_SECONDS = 60;
    private static final int GIT_TIMEOUT_SECONDS = 30;
    private static final int MAX_INLINE_OUTPUT_CHARS = 12_000;
    private static final List<String> FORBIDDEN_SHELL_TOKENS = List.of(
            ";", "&", "|", ">", "<", "`", "$", "\n", "\r"
    );

    private final ObjectMapper objectMapper;
    private final Path runRoot;

    @Autowired
    public LoopCommandExecutor(ObjectMapper objectMapper,
                               @Value("${mateclaw.loop.run-root:data/loop-runs}") String runRoot) {
        this.objectMapper = objectMapper;
        this.runRoot = Path.of(runRoot).toAbsolutePath().normalize();
    }

    LoopCommandExecutor(ObjectMapper objectMapper, Path runRoot) {
        this.objectMapper = objectMapper;
        this.runRoot = runRoot.toAbsolutePath().normalize();
    }

    public LoopExecutionOutcome execute(Long runId, String inputJson, SuperpowerDefinition superpower) {
        Map<String, Object> input = parseInput(inputJson);
        String command = stringValue(input.get("command"));
        String repairCommand = stringValue(input.get("repairCommand"));
        String repoPathValue = stringValue(input.get("repoPath"));
        String branch = stringValue(input.get("branch"));

        SkillManifest.SuperpowerBinding binding = superpower == null ? null : superpower.binding();
        SkillManifest.SuperpowerPolicy policy = binding == null ? null : binding.getPolicy();
        SkillManifest.SuperpowerWorkspace workspace = binding == null ? null : binding.getWorkspace();
        boolean requireHumanBeforePush = policy == null || policy.isRequireHumanBeforePush();
        List<String> allowedCommands = policy == null ? List.of() : policy.getAllowedCommands();
        List<String> allowedRepairCommands = policy == null ? List.of() : policy.getAllowedRepairCommands();
        int maxChangedFiles = policy == null || policy.getMaxChangedFiles() == null
                ? 8
                : Math.max(1, policy.getMaxChangedFiles());

        Path runDir = runRoot.resolve("run-" + runId).normalize();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> artifacts = new ArrayList<>();

        Instant start = Instant.now();
        try {
            Files.createDirectories(runDir);
            validateCommand(command, allowedCommands, "verification command");
            Path repoPath = validateRepoPath(repoPathValue);
            ExecutionWorkspace executionWorkspace = prepareWorkspace(runId, runDir, repoPath, branch, workspace, steps, artifacts);

            Path stdout = runDir.resolve("command.stdout.log");
            Path stderr = runDir.resolve("command.stderr.log");
            CommandResult result = runShellCommand(command, executionWorkspace.cwd(), stdout, stderr, COMMAND_TIMEOUT_SECONDS);
            artifacts.add(artifact("A-stdout", "log", "command.stdout.log", stdout));
            artifacts.add(artifact("A-stderr", "log", "command.stderr.log", stderr));

            if (result.exitCode() == 0 && !result.timedOut()) {
                steps.add(step(
                        "run-command",
                        "passed",
                        List.of("A-stdout", "A-stderr"),
                        commandObservation(command, executionWorkspace.cwd(), result, stdout, stderr),
                        "Verification command completed successfully; no repair was needed."
                ));
                String status = "succeeded";
                String message = "Loop verification command passed in an isolated workspace.";
                Map<String, Object> report = finalReport(status, message, command, repoPath, executionWorkspace.cwd(),
                        result, artifacts, requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
                report.put("attempts", 0);
                report.put("changedFiles", List.of());
                report.put("verification", List.of("target_tests_pass"));
                return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
            }

            steps.add(step(
                    "baseline-failure-reproduced",
                    "passed",
                    List.of("A-stdout", "A-stderr"),
                    commandObservation(command, executionWorkspace.cwd(), result, stdout, stderr),
                    "Baseline failure reproduced in the isolated workspace."
            ));

            if (!StringUtils.hasText(repairCommand)) {
                steps.add(step(
                        "agent-repair",
                        "skipped",
                        List.of(),
                        Map.of("reason", "repairCommand is not configured"),
                        "No repair command was configured, so the loop stopped after reproducing the failure."
                ));
                String status = "needs_human";
                String message = "Baseline failure reproduced; configure an allowed repair command or hand off to a human.";
                Map<String, Object> report = repairReport(status, message, command, repairCommand, repoPath,
                        executionWorkspace.cwd(), result, null, List.of(), artifacts,
                        requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
                return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
            }

            if (!"git_worktree".equalsIgnoreCase(executionWorkspace.mode())) {
                steps.add(step(
                        "agent-repair",
                        "skipped",
                        List.of(),
                        Map.of("mode", executionWorkspace.mode()),
                        "Repair command requires git_worktree isolation to avoid mutating the caller's active workspace."
                ));
                String status = "needs_human";
                String message = "Baseline failure reproduced, but repair requires an isolated git worktree.";
                Map<String, Object> report = repairReport(status, message, command, repairCommand, repoPath,
                        executionWorkspace.cwd(), result, null, List.of(), artifacts,
                        requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
                return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
            }

            validateCommand(repairCommand, allowedRepairCommands, "repair command");

            Path repairStdout = runDir.resolve("agent-repair.stdout.log");
            Path repairStderr = runDir.resolve("agent-repair.stderr.log");
            CommandResult repairResult = runShellCommand(repairCommand, executionWorkspace.cwd(),
                    repairStdout, repairStderr, COMMAND_TIMEOUT_SECONDS);
            artifacts.add(artifact("A-agent-repair-stdout", "log", "agent-repair.stdout.log", repairStdout));
            artifacts.add(artifact("A-agent-repair-stderr", "log", "agent-repair.stderr.log", repairStderr));
            String repairStatus = repairResult.exitCode() == 0 && !repairResult.timedOut() ? "passed" : "failed";
            steps.add(step(
                    "agent-repair",
                    repairStatus,
                    List.of("A-agent-repair-stdout", "A-agent-repair-stderr"),
                    commandObservation(repairCommand, executionWorkspace.cwd(), repairResult, repairStdout, repairStderr),
                    repairStatus.equals("passed")
                            ? "Repair command completed; capturing diff for review."
                            : "Repair command failed; inspect repair artifacts before retrying."
            ));
            if (!repairStatus.equals("passed")) {
                String status = "failed";
                String message = "Repair command failed before re-verification.";
                Map<String, Object> report = repairReport(status, message, command, repairCommand, repoPath,
                        executionWorkspace.cwd(), result, repairResult, List.of(), artifacts,
                        requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
                return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
            }

            DiffReview diffReview = captureDiff(runDir, executionWorkspace.cwd(), workspace, maxChangedFiles, steps, artifacts);
            if (!diffReview.passed()) {
                String status = "needs_human";
                String message = "Repair command changed files, but diff review requires human attention.";
                Map<String, Object> report = repairReport(status, message, command, repairCommand, repoPath,
                        executionWorkspace.cwd(), result, repairResult, diffReview.changedFiles(), artifacts,
                        requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
                return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
            }

            Path reverifyStdout = runDir.resolve("reverify.stdout.log");
            Path reverifyStderr = runDir.resolve("reverify.stderr.log");
            CommandResult reverifyResult = runShellCommand(command, executionWorkspace.cwd(),
                    reverifyStdout, reverifyStderr, COMMAND_TIMEOUT_SECONDS);
            artifacts.add(artifact("A-reverify-stdout", "log", "reverify.stdout.log", reverifyStdout));
            artifacts.add(artifact("A-reverify-stderr", "log", "reverify.stderr.log", reverifyStderr));
            String reverifyStatus = reverifyResult.exitCode() == 0 && !reverifyResult.timedOut() ? "passed" : "failed";
            steps.add(step(
                    "target-tests-pass",
                    reverifyStatus,
                    List.of("A-reverify-stdout", "A-reverify-stderr"),
                    commandObservation(command, executionWorkspace.cwd(), reverifyResult, reverifyStdout, reverifyStderr),
                    reverifyStatus.equals("passed")
                            ? "Target verification passed after the repair command."
                            : "Target verification still fails after the repair command."
            ));

            if (!reverifyStatus.equals("passed")) {
                String status = "failed";
                String message = "Repair was applied, but target verification still fails.";
                Map<String, Object> report = repairReport(status, message, command, repairCommand, repoPath,
                        executionWorkspace.cwd(), result, repairResult, diffReview.changedFiles(), artifacts,
                        requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
                report.put("reverifyExitCode", reverifyResult.exitCode());
                report.put("reverifyTimedOut", reverifyResult.timedOut());
                return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
            }

            steps.add(step(
                    "human-confirmation",
                    requireHumanBeforePush ? "inconclusive" : "passed",
                    List.of("A-diff-patch"),
                    Map.of(
                            "changedFiles", diffReview.changedFiles(),
                            "humanGateRequiredBeforePush", requireHumanBeforePush
                    ),
                    requireHumanBeforePush
                            ? "Repair is verified, but human confirmation is required before merge or push."
                            : "Repair is verified and no human gate is required by policy."
            ));

            String status = requireHumanBeforePush ? "needs_human" : "succeeded";
            String message = requireHumanBeforePush
                    ? "Repair verified; human confirmation is required before push."
                    : "Repair verified successfully.";
            Map<String, Object> report = repairReport(status, message, command, repairCommand, repoPath,
                    executionWorkspace.cwd(), result, repairResult, diffReview.changedFiles(), artifacts,
                    requireHumanBeforePush, Duration.between(start, Instant.now()).toMillis());
            report.put("reverifyExitCode", reverifyResult.exitCode());
            report.put("reverifyTimedOut", reverifyResult.timedOut());
            report.put("verification", List.of("baseline_failure_reproduced", "diff_review_passed", "target_tests_pass"));
            return new LoopExecutionOutcome(status, message, toJson(steps), toJson(artifacts), toJson(report));
        } catch (Exception e) {
            log.warn("[LoopEngineering] run {} failed before completion: {}", runId, e.getMessage());
            steps.add(step(
                    "validate-and-prepare",
                    "failed",
                    List.of(),
                    Map.of("error", e.getMessage()),
                    "Loop execution could not start safely."
            ));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", "failed");
            report.put("message", e.getMessage());
            report.put("humanGateRequired", true);
            report.put("durationMs", Duration.between(start, Instant.now()).toMillis());
            return new LoopExecutionOutcome("failed", e.getMessage(), toJson(steps), toJson(artifacts), toJson(report));
        }
    }

    private Map<String, Object> commandObservation(String command,
                                                   Path cwd,
                                                   CommandResult result,
                                                   Path stdout,
                                                   Path stderr) {
        return Map.of(
                "command", command,
                "cwd", cwd.toString(),
                "exitCode", result.exitCode(),
                "timedOut", result.timedOut(),
                "durationMs", result.durationMs(),
                "stdoutTail", inlineFile(stdout),
                "stderrTail", inlineFile(stderr)
        );
    }

    private DiffReview captureDiff(Path runDir,
                                   Path cwd,
                                   SkillManifest.SuperpowerWorkspace workspace,
                                   int maxChangedFiles,
                                   List<Map<String, Object>> steps,
                                   List<Map<String, Object>> artifacts) throws IOException, InterruptedException {
        Path diffPatch = runDir.resolve("diff.patch");
        Path diffStderr = runDir.resolve("git-diff.stderr.log");
        CommandResult diffResult = runProcess(List.of("git", "-C", cwd.toString(), "diff", "--", "."),
                cwd, diffPatch, diffStderr, GIT_TIMEOUT_SECONDS);
        artifacts.add(artifact("A-diff-patch", "patch", "diff.patch", diffPatch));
        artifacts.add(artifact("A-diff-stderr", "log", "git-diff.stderr.log", diffStderr));

        Path changedFilesLog = runDir.resolve("changed-files.log");
        Path changedFilesErr = runDir.resolve("changed-files.stderr.log");
        CommandResult changedResult = runProcess(List.of("git", "-C", cwd.toString(), "diff", "--name-only", "--", "."),
                cwd, changedFilesLog, changedFilesErr, GIT_TIMEOUT_SECONDS);
        artifacts.add(artifact("A-changed-files", "log", "changed-files.log", changedFilesLog));

        List<String> changedFiles = parseLines(inlineFile(changedFilesLog));
        List<String> violations = changedFileViolations(changedFiles, workspace, maxChangedFiles);
        boolean passed = diffResult.exitCode() == 0
                && changedResult.exitCode() == 0
                && !diffResult.timedOut()
                && !changedResult.timedOut()
                && !changedFiles.isEmpty()
                && violations.isEmpty();

        String interpretation;
        if (changedFiles.isEmpty()) {
            interpretation = "Repair command did not produce a git diff.";
        } else if (!violations.isEmpty()) {
            interpretation = "Diff review found policy violations.";
        } else {
            interpretation = "Diff is within changed-file count and allowed path policy.";
        }
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("changedFiles", changedFiles);
        observation.put("maxChangedFiles", maxChangedFiles);
        observation.put("allowedPaths", workspace == null ? List.of() : workspace.getAllowedPaths());
        observation.put("violations", violations);
        observation.put("diffTimedOut", diffResult.timedOut());
        observation.put("diffExitCode", diffResult.exitCode());
        steps.add(step("diff-review", passed ? "passed" : "inconclusive",
                List.of("A-diff-patch", "A-changed-files"), observation, interpretation));
        return new DiffReview(passed, changedFiles, violations);
    }

    private List<String> changedFileViolations(List<String> changedFiles,
                                               SkillManifest.SuperpowerWorkspace workspace,
                                               int maxChangedFiles) {
        List<String> violations = new ArrayList<>();
        if (changedFiles.size() > maxChangedFiles) {
            violations.add("changed file count " + changedFiles.size() + " exceeds maxChangedFiles " + maxChangedFiles);
        }
        List<String> allowedPaths = workspace == null ? List.of() : workspace.getAllowedPaths();
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return violations;
        }
        List<String> normalizedAllowed = allowedPaths.stream()
                .filter(StringUtils::hasText)
                .map(LoopCommandExecutor::normalizeRepoRelativePath)
                .toList();
        for (String file : changedFiles) {
            String normalizedFile = normalizeRepoRelativePath(file);
            boolean allowed = normalizedAllowed.stream().anyMatch(allowedPath ->
                    normalizedFile.equals(allowedPath) || normalizedFile.startsWith(allowedPath + "/"));
            if (!allowed) {
                violations.add("changed file is outside allowedPaths: " + normalizedFile);
            }
        }
        return violations;
    }

    private Map<String, Object> repairReport(String status,
                                             String message,
                                             String command,
                                             String repairCommand,
                                             Path repoPath,
                                             Path executionDir,
                                             CommandResult baselineResult,
                                             CommandResult repairResult,
                                             List<String> changedFiles,
                                             List<Map<String, Object>> artifacts,
                                             boolean requireHumanBeforePush,
                                             long totalDurationMs) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("conclusion", message);
        report.put("message", message);
        report.put("command", command);
        report.put("repairCommand", StringUtils.hasText(repairCommand) ? repairCommand : null);
        report.put("repoPath", repoPath.toString());
        report.put("executionDir", executionDir.toString());
        report.put("baselineExitCode", baselineResult.exitCode());
        report.put("baselineTimedOut", baselineResult.timedOut());
        if (repairResult != null) {
            report.put("repairExitCode", repairResult.exitCode());
            report.put("repairTimedOut", repairResult.timedOut());
        }
        report.put("attempts", repairResult == null ? 0 : 1);
        report.put("changedFiles", changedFiles);
        report.put("artifacts", artifacts);
        report.put("artifactIds", artifacts.stream().map(a -> a.get("id")).toList());
        report.put("humanGateRequired", requireHumanBeforePush);
        report.put("humanGateRequiredBeforePush", requireHumanBeforePush);
        report.put("totalDurationMs", totalDurationMs);
        report.put("verification", List.of("baseline_failure_reproduced"));
        report.put("nextAction", status.equals("needs_human")
                ? "Review diff.patch and approve, retry with another repair command, or hand off to a human."
                : "Review artifacts before closing the loop.");
        return report;
    }

    private ExecutionWorkspace prepareWorkspace(Long runId,
                                                Path runDir,
                                                Path repoPath,
                                                String branch,
                                                SkillManifest.SuperpowerWorkspace workspace,
                                                List<Map<String, Object>> steps,
                                                List<Map<String, Object>> artifacts) throws IOException, InterruptedException {
        String isolation = workspace == null ? "none" : workspace.getIsolation();
        if (!"git_worktree".equalsIgnoreCase(StringUtils.hasText(isolation) ? isolation : "none")) {
            steps.add(step("prepare-workspace", "passed", List.of(),
                    Map.of("mode", "direct", "cwd", repoPath.toString()),
                    "Using the provided repository path directly."));
            return new ExecutionWorkspace("direct", repoPath);
        }

        Path worktree = runDir.resolve("worktree").normalize();
        Files.createDirectories(runDir);
        if (Files.exists(worktree)) {
            try (var files = Files.list(worktree)) {
                if (files.findAny().isPresent()) {
                    throw new IllegalStateException("Run worktree already exists and is not empty: " + worktree);
                }
            }
        }

        String ref = StringUtils.hasText(branch) ? branch.trim() : "HEAD";
        if (!ref.matches("[A-Za-z0-9._/@-]+")) {
            throw new IllegalArgumentException("Branch/ref contains unsupported characters");
        }

        Path stdout = runDir.resolve("git-worktree.stdout.log");
        Path stderr = runDir.resolve("git-worktree.stderr.log");
        CommandResult result = runProcess(List.of(
                "git", "-C", repoPath.toString(), "worktree", "add", "--detach", worktree.toString(), ref
        ), repoPath, stdout, stderr, GIT_TIMEOUT_SECONDS);
        artifacts.add(artifact("A-worktree-stdout", "log", "git-worktree.stdout.log", stdout));
        artifacts.add(artifact("A-worktree-stderr", "log", "git-worktree.stderr.log", stderr));

        if (result.exitCode() != 0 || result.timedOut()) {
            steps.add(step("prepare-worktree", "failed", List.of("A-worktree-stdout", "A-worktree-stderr"),
                    Map.of(
                            "repoPath", repoPath.toString(),
                            "worktreePath", worktree.toString(),
                            "ref", ref,
                            "exitCode", result.exitCode(),
                            "timedOut", result.timedOut(),
                            "stderrTail", inlineFile(stderr)
                    ),
                    "Git worktree creation failed."));
            throw new IllegalStateException("Failed to create git worktree: " + firstNonBlank(inlineFile(stderr), inlineFile(stdout)));
        }

        artifacts.add(Map.of(
                "id", "A-worktree",
                "type", "workspace",
                "name", "worktree",
                "path", worktree.toString()
        ));
        steps.add(step("prepare-worktree", "passed", List.of("A-worktree"),
                Map.of("repoPath", repoPath.toString(), "worktreePath", worktree.toString(), "ref", ref),
                "Created an isolated git worktree for this run."));
        return new ExecutionWorkspace("git_worktree", worktree);
    }

    private CommandResult runShellCommand(String command, Path cwd, Path stdout, Path stderr, int timeoutSeconds)
            throws IOException, InterruptedException {
        String shell = selectShell();
        return runProcess(List.of(shell, "-lc", command), cwd, stdout, stderr, timeoutSeconds);
    }

    private CommandResult runProcess(List<String> command, Path cwd, Path stdout, Path stderr, int timeoutSeconds)
            throws IOException, InterruptedException {
        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectOutput(stdout.toFile());
        pb.redirectError(stderr.toFile());
        pb.environment().keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return upper.contains("KEY") || upper.contains("SECRET") || upper.contains("TOKEN")
                    || upper.contains("PASSWORD") || upper.contains("CREDENTIAL");
        });
        Process process = pb.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            killProcessTree(process);
            return new CommandResult(-1, true, Duration.between(start, Instant.now()).toMillis());
        }
        return new CommandResult(process.exitValue(), false, Duration.between(start, Instant.now()).toMillis());
    }

    private void validateCommand(String command, List<String> allowedCommands, String label) {
        if (!StringUtils.hasText(command)) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = normalizeCommand(command);
        for (String token : FORBIDDEN_SHELL_TOKENS) {
            if (normalized.contains(token)) {
                throw new IllegalArgumentException(label + " contains unsupported shell token: " + token.replace("\n", "\\n").replace("\r", "\\r"));
            }
        }
        List<String> allowed = allowedCommands == null ? List.of() : allowedCommands.stream()
                .filter(StringUtils::hasText)
                .map(LoopCommandExecutor::normalizeCommand)
                .toList();
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("Superpower does not define allowed commands for " + label);
        }
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + " is not allowed by this Superpower: " + normalized);
        }
    }

    private Path validateRepoPath(String repoPathValue) throws IOException, InterruptedException {
        if (!StringUtils.hasText(repoPathValue)) {
            throw new IllegalArgumentException("repoPath is required");
        }
        Path repoPath = Path.of(repoPathValue.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoPath)) {
            throw new IllegalArgumentException("repoPath is not a directory: " + repoPath);
        }
        Path stdout = Files.createTempFile("loop_git_revparse_", ".out");
        Path stderr = Files.createTempFile("loop_git_revparse_", ".err");
        try {
            CommandResult result = runProcess(List.of("git", "-C", repoPath.toString(), "rev-parse", "--is-inside-work-tree"),
                    repoPath, stdout, stderr, GIT_TIMEOUT_SECONDS);
            if (result.exitCode() != 0 || result.timedOut()) {
                throw new IllegalArgumentException("repoPath is not a git work tree: " + repoPath);
            }
            return repoPath;
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private Map<String, Object> parseInput(String inputJson) {
        if (!StringUtils.hasText(inputJson)) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(inputJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid loop run inputJson", e);
        }
    }

    private static String normalizeCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeRepoRelativePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static List<String> parseLines(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return value.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> !line.startsWith("...[TRUNCATED "))
                .toList();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String selectShell() {
        String shell = System.getenv("SHELL");
        if (StringUtils.hasText(shell) && Files.isExecutable(Path.of(shell))) {
            return shell;
        }
        return "/bin/sh";
    }

    private static void killProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignored) {
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> artifact(String id, String type, String name, Path path) throws IOException {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("id", id);
        artifact.put("type", type);
        artifact.put("name", name);
        artifact.put("path", path.toString());
        artifact.put("sizeBytes", Files.exists(path) ? Files.size(path) : 0L);
        return artifact;
    }

    private static Map<String, Object> step(String stepId,
                                            String status,
                                            List<String> evidenceIds,
                                            Map<String, Object> data,
                                            String interpretation) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepId", stepId);
        step.put("status", status);
        step.put("evidenceIds", evidenceIds);
        step.put("observation", data);
        step.put("interpretation", interpretation);
        step.put("nextDecision", "passed".equals(status) ? "continue" : "need_human");
        return step;
    }

    private Map<String, Object> finalReport(String status,
                                            String message,
                                            String command,
                                            Path repoPath,
                                            Path executionDir,
                                            CommandResult result,
                                            List<Map<String, Object>> artifacts,
                                            boolean requireHumanBeforePush,
                                            long totalDurationMs) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("message", message);
        report.put("command", command);
        report.put("repoPath", repoPath.toString());
        report.put("executionDir", executionDir.toString());
        report.put("exitCode", result.exitCode());
        report.put("timedOut", result.timedOut());
        report.put("commandDurationMs", result.durationMs());
        report.put("totalDurationMs", totalDurationMs);
        report.put("artifacts", artifacts);
        report.put("humanGateRequiredBeforePush", requireHumanBeforePush);
        report.put("nextAction", status.equals("succeeded")
                ? "Review artifacts and decide whether to continue into agent repair."
                : "Inspect logs, refine the command or hand off to a human.");
        return report;
    }

    private String inlineFile(Path path) {
        try {
            if (!Files.exists(path)) return "";
            String value = Files.readString(path, StandardCharsets.UTF_8);
            if (value.length() <= MAX_INLINE_OUTPUT_CHARS) return value;
            return value.substring(0, MAX_INLINE_OUTPUT_CHARS)
                    + "\n\n...[TRUNCATED " + value.length() + " chars]...";
        } catch (Exception e) {
            return "[failed to read artifact: " + e.getMessage() + "]";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize loop execution payload", e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public record LoopExecutionOutcome(
            String status,
            String message,
            String stepResultsJson,
            String artifactsJson,
            String finalReportJson
    ) {
    }

    private record ExecutionWorkspace(String mode, Path cwd) {
    }

    private record CommandResult(int exitCode, boolean timedOut, long durationMs) {
    }

    private record DiffReview(boolean passed, List<String> changedFiles, List<String> violations) {
    }
}
