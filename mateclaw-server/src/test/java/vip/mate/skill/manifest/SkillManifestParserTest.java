package vip.mate.skill.manifest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-090 Phase 2 — manifest parser regression tests.
 *
 * <p>Covers: identity fields, allowed-tools alias, requires/features
 * matrix, settings/dashboard, self-evolution defaults, knowledge block,
 * and legacy fallback (no v3 frontmatter).
 */
class SkillManifestParserTest {

    private SkillManifestParser parser;

    @BeforeEach
    void setUp() {
        parser = new SkillManifestParser(new SkillFrontmatterParser());
    }

    @Test
    @DisplayName("parses the full v3.1 manifest")
    void parsesFullManifest() {
        String content = """
            ---
            id: clip-generator
            name: clip-generator
            description: Long video to viral short clips
            icon: "🎬"
            version: 1.2.0
            author: matevip
            type: code
            category: content
            allowed-tools: [shell_exec, file_read]
            platforms: [macos, linux]
            requires:
              - key: ffmpeg
                type: binary
                check: ffmpeg
                optional: false
                description: FFmpeg binary
                install:
                  macos: brew install ffmpeg
                  linux_apt: sudo apt install ffmpeg
              - key: groq_key
                type: api_key
                check: GROQ_API_KEY
            features:
              - id: trim_video
                label: "Trim video"
                requires: [ffmpeg]
                platforms: [macos, linux, windows]
              - id: auto_captions
                label: "Auto captions"
                requires: [ffmpeg, groq_key]
                fallback_message: "Install whisper for local STT"
            settings:
              - key: stt_provider
                label: STT
                type: select
                default: auto
                options:
                  - value: auto
                  - value: groq_whisper
            requires-model: [vision, function_calling]
            dashboard:
              metrics:
                - label: Clips
                  memory_key: clip_jobs_done
                  format: number
            self-evolution:
              lessons_enabled: false
              lessons_max_entries: 12
              memory_writes_allowed: true
            ---
            # body
            """;

        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertEquals("clip-generator", m.getId());
        assertEquals("code", m.getType());
        assertEquals("matevip", m.getAuthor());
        assertEquals("1.2.0", m.getVersion());
        assertEquals(2, m.getAllowedTools().size());
        assertTrue(m.getAllowedTools().contains("shell_exec"));
        assertEquals(2, m.getRequires().size());
        assertEquals("ffmpeg", m.getRequires().get(0).getKey());
        assertEquals("binary", m.getRequires().get(0).getType());
        assertEquals("brew install ffmpeg", m.getRequires().get(0).getInstall().get("macos"));
        assertEquals(2, m.getFeatures().size());
        assertEquals("trim_video", m.getFeatures().get(0).getId());
        assertEquals(1, m.getFeatures().get(0).getRequires().size());
        assertEquals("Install whisper for local STT", m.getFeatures().get(1).getFallbackMessage());
        assertEquals(1, m.getSettings().size());
        assertEquals("stt_provider", m.getSettings().get(0).getKey());
        assertEquals(2, m.getRequiresModel().size());
        assertEquals(1, m.getDashboardMetrics().size());
        assertFalse(m.getSelfEvolution().isLessonsEnabled());
        assertEquals(12, m.getSelfEvolution().getLessonsMaxEntries());
    }

    @Test
    @DisplayName("falls back to legacy dependencies.tools when allowed-tools is absent")
    void fallsBackToLegacyDependencyTools() {
        // Most existing SKILL.md files (pre-v3) declare tools via the
        // dependencies.tools list, not v3 allowed-tools. This is the
        // root cause of the Tools tab rendering empty for shipped
        // skills. Locking the fallback in regression form.
        String content = """
            ---
            name: legacy-skill
            description: legacy-style declaration
            dependencies:
              tools: [shell_exec, file_read, web_fetch]
              commands: [python3]
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertEquals(3, m.getAllowedTools().size(), "allowedTools should fall back to dependencies.tools");
        assertTrue(m.getAllowedTools().contains("shell_exec"));
        assertTrue(m.getAllowedTools().contains("file_read"));
        assertTrue(m.getAllowedTools().contains("web_fetch"));
    }

    @Test
    @DisplayName("v3 allowed-tools wins over legacy dependencies.tools")
    void v3AllowedToolsWinsOverLegacy() {
        String content = """
            ---
            name: hybrid-skill
            allowed-tools: [v3_only_tool]
            dependencies:
              tools: [legacy_tool]
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertEquals(1, m.getAllowedTools().size());
        assertEquals("v3_only_tool", m.getAllowedTools().get(0),
                "v3 allowed-tools should take precedence over legacy dependencies.tools");
    }

    @Test
    @DisplayName("supports allowed_tools underscore alias")
    void supportsAllowedToolsAlias() {
        String content = """
            ---
            name: x
            allowed_tools:
              - foo
              - bar
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertEquals(2, m.getAllowedTools().size());
    }

    @Test
    @DisplayName("ckjia-shopping declares MCP tools and bumped bundle version")
    void ckjiaShoppingDeclaresMcpToolsAndBumpedVersion() throws Exception {
        String content = readClasspathText("skills/ckjia-shopping/SKILL.md");

        SkillManifest m = parser.parse(content);

        assertNotNull(m);
        assertEquals("mcp", m.getType());
        assertEquals("1.0.1", m.getVersion(),
                "bundle version must bump whenever shipped SKILL.md behavior changes");
        assertEquals(Set.of("ckjia_shopping_recommend", "ckjia_image_recognize", "ckjia_ping"),
                Set.copyOf(m.getAllowedTools()),
                "explicit skill bindings expand only allowed-tools, not prose tool names");
    }

    @Test
    @DisplayName("synthesizes requires from legacy dependencies block")
    void synthesizesLegacyDependencies() {
        String content = """
            ---
            name: legacy-skill
            description: legacy
            dependencies:
              commands: [python3, ffmpeg]
              env: [OPENAI_API_KEY]
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        // No explicit requires[] → synthesized from legacy commands+env.
        assertEquals(3, m.getRequires().size());
        assertEquals("cmd:python3", m.getRequires().get(0).getKey());
        assertEquals("binary", m.getRequires().get(0).getType());
        assertEquals("env:OPENAI_API_KEY", m.getRequires().get(2).getKey());
        assertEquals("env_var", m.getRequires().get(2).getType());
    }

    @Test
    @DisplayName("returns null for content with no frontmatter")
    void returnsNullForNoFrontmatter() {
        SkillManifest m = parser.parse("# Just a markdown file\n\nNo frontmatter here.");
        assertNull(m);
    }

    @Test
    @DisplayName("self-evolution defaults are on when block is absent")
    void selfEvolutionDefaults() {
        String content = """
            ---
            name: minimal
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertTrue(m.getSelfEvolution().isLessonsEnabled());
        assertEquals(50, m.getSelfEvolution().getLessonsMaxEntries());
        assertTrue(m.getSelfEvolution().isMemoryWritesAllowed());
    }

    @Test
    @DisplayName("knowledge block parses bind_kb / retrieval / citation")
    void knowledgeBlockParses() {
        String content = """
            ---
            name: tcm-qa
            type: knowledge
            knowledge:
              bind_kb: tcm-classics
              retrieval: hybrid
              top_k: 8
              citation: required
              rerank: true
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertNotNull(m.getKnowledge());
        assertEquals("tcm-classics", m.getKnowledge().getBindKb());
        assertEquals("hybrid", m.getKnowledge().getRetrieval());
        assertEquals(8, m.getKnowledge().getTopK());
        assertEquals("required", m.getKnowledge().getCitation());
        assertTrue(m.getKnowledge().isRerank());
        assertNull(m.getKnowledge().getBoundKbId());
    }

    @Test
    @DisplayName("acp block parses endpoint / system_prefix / cwd")
    void acpBlockParses() {
        String content = """
            ---
            name: codex-helper
            type: acp
            acp:
              endpoint: codex
              system_prefix: "Be concise."
              cwd: /tmp/project
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertEquals("acp", m.getType());
        assertNotNull(m.getAcp());
        assertEquals("codex", m.getAcp().getEndpoint());
        assertEquals("Be concise.", m.getAcp().getSystemPrefix());
        assertEquals("/tmp/project", m.getAcp().getCwd());
        assertNull(m.getAcp().getResolvedEndpointId());
    }

    @Test
    @DisplayName("troubleshooting block parses SOP routing metadata")
    void troubleshootingBlockParses() {
        String content = """
            ---
            name: api-service-5xx
            type: prompt
            category: troubleshooting
            troubleshooting:
              domain: api_service
              scenario: http_5xx
              match:
                severities: [P1, P2]
                labels: [serviceName, env, cluster, endpoint]
                keywords: [500, 502, timeout]
              requiredEvidence: [metrics, logs, release]
              optionalEvidence: [k8s, gateway]
              outputSchema: sop-checklist-v1
              owner: platform-sre
              reviewCycleDays: 90
            ---
            body
            """;

        SkillManifest m = parser.parse(content);

        assertNotNull(m);
        assertEquals("troubleshooting", m.getCategory());
        assertNotNull(m.getTroubleshooting());
        assertEquals("api_service", m.getTroubleshooting().getDomain());
        assertEquals("http_5xx", m.getTroubleshooting().getScenario());
        assertEquals(2, m.getTroubleshooting().getMatch().getSeverities().size());
        assertEquals(4, m.getTroubleshooting().getMatch().getLabels().size());
        assertEquals(3, m.getTroubleshooting().getMatch().getKeywords().size());
        assertEquals("metrics", m.getTroubleshooting().getRequiredEvidence().get(0));
        assertEquals("gateway", m.getTroubleshooting().getOptionalEvidence().get(1));
        assertEquals("sop-checklist-v1", m.getTroubleshooting().getOutputSchema());
        assertEquals("platform-sre", m.getTroubleshooting().getOwner());
        assertEquals(90, m.getTroubleshooting().getReviewCycleDays());
    }

    @Test
    @DisplayName("superpower block parses loop engineering metadata")
    void superpowerBlockParses() {
        String content = """
            ---
            name: loop-fix-failing-test
            type: prompt
            category: superpower
            superpower:
              domain: code_refix
              scenario: fix_failing_test
              trigger:
                type: manual
                sources: [manual, ci_failure]
              workspace:
                isolation: git_worktree
                allowedPaths: [src, test]
              policy:
                maxIterations: 3
                maxChangedFiles: 8
                requireHumanBeforePush: true
                allowedCommands: [mvn test, npm test]
                allowedRepairCommands: [npm run loop:repair]
              verification:
                required: [baseline_failure_reproduced, target_tests_pass, diff_review_passed]
                recommended: [lint_passed]
              outputs: [evidence.md, diff.patch, review.md, result.json]
              owner: engineering-platform
              reviewCycleDays: 90
            ---
            body
            """;

        SkillManifest m = parser.parse(content);

        assertNotNull(m);
        assertEquals("superpower", m.getCategory());
        assertNotNull(m.getSuperpower());
        assertEquals("code_refix", m.getSuperpower().getDomain());
        assertEquals("fix_failing_test", m.getSuperpower().getScenario());
        assertEquals("manual", m.getSuperpower().getTrigger().getType());
        assertEquals("ci_failure", m.getSuperpower().getTrigger().getSources().get(1));
        assertEquals("git_worktree", m.getSuperpower().getWorkspace().getIsolation());
        assertEquals("src", m.getSuperpower().getWorkspace().getAllowedPaths().get(0));
        assertEquals(3, m.getSuperpower().getPolicy().getMaxIterations());
        assertEquals(8, m.getSuperpower().getPolicy().getMaxChangedFiles());
        assertTrue(m.getSuperpower().getPolicy().isRequireHumanBeforePush());
        assertEquals("npm test", m.getSuperpower().getPolicy().getAllowedCommands().get(1));
        assertEquals("npm run loop:repair", m.getSuperpower().getPolicy().getAllowedRepairCommands().get(0));
        assertEquals("target_tests_pass", m.getSuperpower().getVerification().getRequired().get(1));
        assertEquals("lint_passed", m.getSuperpower().getVerification().getRecommended().get(0));
        assertEquals("review.md", m.getSuperpower().getOutputs().get(2));
        assertEquals("engineering-platform", m.getSuperpower().getOwner());
        assertEquals(90, m.getSuperpower().getReviewCycleDays());
    }

    @Test
    @DisplayName("preserves unknown keys in extras for forward-compat")
    void preservesUnknownKeysInExtras() {
        String content = """
            ---
            name: future-skill
            future_field: someValue
            another_one: 42
            ---
            body
            """;
        SkillManifest m = parser.parse(content);
        assertNotNull(m);
        assertEquals("someValue", m.getExtras().get("future_field"));
        assertEquals(42, m.getExtras().get("another_one"));
    }

    private static String readClasspathText(String path) throws Exception {
        try (InputStream is = SkillManifestParserTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "missing classpath resource: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
