package vip.mate.loop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.loop.model.SuperpowerDefinition;
import vip.mate.skill.manifest.SkillManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopCommandExecutorTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesAllowedCommandInsideGitWorktree() throws Exception {
        Assumptions.assumeTrue(commandSucceeds(tempDir, "git", "--version"), "git is required for this test");
        Path repo = initGitRepo(tempDir.resolve("repo"));
        LoopCommandExecutor executor = new LoopCommandExecutor(objectMapper, tempDir.resolve("loop-runs"));

        LoopCommandExecutor.LoopExecutionOutcome outcome = executor.execute(
                1001L,
                objectMapper.writeValueAsString(Map.of(
                        "repoPath", repo.toString(),
                        "command", "printf loop-ok"
                )),
                superpower("printf loop-ok")
        );

        assertEquals("succeeded", outcome.status());
        assertTrue(outcome.stepResultsJson().contains("prepare-worktree"));
        assertTrue(outcome.stepResultsJson().contains("loop-ok"));
        assertTrue(outcome.artifactsJson().contains("command.stdout.log"));
        assertTrue(Files.isDirectory(tempDir.resolve("loop-runs/run-1001/worktree")));
    }

    @Test
    void rejectsShellControlTokensBeforeExecution() throws Exception {
        LoopCommandExecutor executor = new LoopCommandExecutor(objectMapper, tempDir.resolve("loop-runs"));

        LoopCommandExecutor.LoopExecutionOutcome outcome = executor.execute(
                1002L,
                objectMapper.writeValueAsString(Map.of(
                        "repoPath", tempDir.toString(),
                        "command", "printf loop-ok; rm -rf /"
                )),
                superpower("printf loop-ok")
        );

        assertEquals("failed", outcome.status());
        assertTrue(outcome.finalReportJson().contains("unsupported shell token"));
    }

    @Test
    void runsRepairCommandReverifiesAndStopsAtHumanGate() throws Exception {
        Assumptions.assumeTrue(commandSucceeds(tempDir, "git", "--version"), "git is required for this test");
        Path repo = initRepairableGitRepo(tempDir.resolve("repo"));
        LoopCommandExecutor executor = new LoopCommandExecutor(objectMapper, tempDir.resolve("loop-runs"));

        LoopCommandExecutor.LoopExecutionOutcome outcome = executor.execute(
                1003L,
                objectMapper.writeValueAsString(Map.of(
                        "repoPath", repo.toString(),
                        "command", "sh check.sh",
                        "repairCommand", "sh repair.sh"
                )),
                superpower("sh check.sh", "sh repair.sh")
        );

        assertEquals("needs_human", outcome.status());
        assertTrue(outcome.stepResultsJson().contains("baseline-failure-reproduced"));
        assertTrue(outcome.stepResultsJson().contains("agent-repair"));
        assertTrue(outcome.stepResultsJson().contains("target-tests-pass"));
        assertTrue(outcome.stepResultsJson().contains("human-confirmation"));
        assertTrue(outcome.artifactsJson().contains("diff.patch"));
        assertTrue(outcome.finalReportJson().contains("src/value.txt"));
    }

    @Test
    void stopsAfterReproductionWhenRepairCommandIsMissing() throws Exception {
        Assumptions.assumeTrue(commandSucceeds(tempDir, "git", "--version"), "git is required for this test");
        Path repo = initRepairableGitRepo(tempDir.resolve("repo"));
        LoopCommandExecutor executor = new LoopCommandExecutor(objectMapper, tempDir.resolve("loop-runs"));

        LoopCommandExecutor.LoopExecutionOutcome outcome = executor.execute(
                1004L,
                objectMapper.writeValueAsString(Map.of(
                        "repoPath", repo.toString(),
                        "command", "sh check.sh"
                )),
                superpower("sh check.sh", "sh repair.sh")
        );

        assertEquals("needs_human", outcome.status());
        assertTrue(outcome.stepResultsJson().contains("baseline-failure-reproduced"));
        assertTrue(outcome.stepResultsJson().contains("repairCommand is not configured"));
    }

    private SuperpowerDefinition superpower(String allowedCommand) {
        return superpower(allowedCommand, null);
    }

    private SuperpowerDefinition superpower(String allowedCommand, String allowedRepairCommand) {
        SkillManifest.SuperpowerBinding binding = SkillManifest.SuperpowerBinding.builder()
                .domain("code_refix")
                .scenario("fix_failing_test")
                .workspace(SkillManifest.SuperpowerWorkspace.builder()
                        .isolation("git_worktree")
                        .allowedPaths(List.of("src"))
                        .build())
                .policy(SkillManifest.SuperpowerPolicy.builder()
                        .allowedCommands(List.of(allowedCommand))
                        .allowedRepairCommands(allowedRepairCommand == null ? List.of() : List.of(allowedRepairCommand))
                        .requireHumanBeforePush(true)
                        .maxChangedFiles(3)
                        .build())
                .build();
        return new SuperpowerDefinition(
                1L,
                "loop-fix-failing-test",
                "test",
                "0.1.0",
                true,
                null,
                binding,
                "",
                ""
        );
    }

    private static Path initGitRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        run(repo, "git", "init");
        run(repo, "git", "config", "user.email", "loop@example.test");
        run(repo, "git", "config", "user.name", "Loop Test");
        Files.writeString(repo.resolve("README.md"), "loop\n");
        run(repo, "git", "add", "README.md");
        run(repo, "git", "commit", "-m", "init");
        return repo;
    }

    private static Path initRepairableGitRepo(Path repo) throws Exception {
        initGitRepo(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/value.txt"), "bad\n");
        Files.writeString(repo.resolve("check.sh"), """
                #!/bin/sh
                grep -q fixed src/value.txt
                """);
        Files.writeString(repo.resolve("repair.sh"), """
                #!/bin/sh
                printf 'fixed\\n' > src/value.txt
                """);
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-m", "add repair fixture");
        return repo;
    }

    private static boolean commandSucceeds(Path cwd, String... command) throws Exception {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command));
        }
    }
}
