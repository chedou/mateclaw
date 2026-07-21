package vip.mate.loop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.loop.dto.LoopSuperpowerPreviewRequest;
import vip.mate.loop.dto.LoopSuperpowerPreviewResponse;
import vip.mate.loop.dto.LoopSuperpowerSummary;
import vip.mate.loop.model.SuperpowerDefinition;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.manifest.SkillManifestParser;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperpowerRegistryService {

    private final SkillMapper skillMapper;
    private final SkillManifestParser manifestParser;
    private final SkillFrontmatterParser frontmatterParser;
    private final ObjectMapper objectMapper;

    public List<SuperpowerDefinition> listSuperpowers(long workspaceId) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getEnabled, true)
                        .eq(SkillEntity::getDeleted, 0)
                        .and(w -> w.eq(SkillEntity::getBuiltin, true)
                                .or().eq(SkillEntity::getWorkspaceId, workspaceId)))
                .stream()
                .map(this::toDefinition)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(SuperpowerDefinition::domain, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SuperpowerDefinition::scenario, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SuperpowerDefinition::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public Optional<SuperpowerDefinition> findBySkillId(long workspaceId, Long skillId) {
        if (skillId == null) return Optional.empty();
        return listSuperpowers(workspaceId).stream()
                .filter(definition -> skillId.equals(definition.skillId()))
                .findFirst();
    }

    public Optional<SuperpowerDefinition> findByDomainAndScenario(long workspaceId, String domain, String scenario) {
        if (!StringUtils.hasText(domain) || !StringUtils.hasText(scenario)) return Optional.empty();
        return listSuperpowers(workspaceId).stream()
                .filter(definition -> domain.equalsIgnoreCase(definition.domain())
                        && scenario.equalsIgnoreCase(definition.scenario()))
                .findFirst();
    }

    public LoopSuperpowerPreviewResponse preview(long workspaceId, LoopSuperpowerPreviewRequest request) {
        List<SuperpowerDefinition> definitions = listSuperpowers(workspaceId);
        List<LoopSuperpowerSummary> candidates = definitions.stream()
                .map(LoopSuperpowerSummary::from)
                .toList();
        if (definitions.isEmpty()) {
            return new LoopSuperpowerPreviewResponse(null, 0.0, List.of(),
                    List.of("superpower_catalog"), candidates);
        }

        SuperpowerDefinition selected = chooseBest(definitions, request);
        List<String> reasons = new ArrayList<>();
        List<String> missingSignals = new ArrayList<>();
        double confidence = 0.45;

        String command = request == null ? null : request.command();
        String goal = request == null ? null : request.goal();
        String repoPath = request == null ? null : request.repoPath();

        if (StringUtils.hasText(command) && command.toLowerCase().contains("test")) {
            reasons.add("command_mentions_test");
            confidence += 0.3;
        }
        if (StringUtils.hasText(goal) && containsAny(goal, "fail", "失败", "test", "测试")) {
            reasons.add("goal_mentions_failing_test");
            confidence += 0.2;
        }
        if (!StringUtils.hasText(repoPath)) missingSignals.add("repoPath");
        if (!StringUtils.hasText(command)) missingSignals.add("command");
        if (!StringUtils.hasText(goal)) missingSignals.add("goal");
        if (reasons.isEmpty()) reasons.add("default_first_available_superpower");

        return new LoopSuperpowerPreviewResponse(
                LoopSuperpowerSummary.from(selected),
                Math.min(confidence, 0.98),
                reasons,
                missingSignals,
                candidates
        );
    }

    private SuperpowerDefinition chooseBest(List<SuperpowerDefinition> definitions, LoopSuperpowerPreviewRequest request) {
        String text = ((request == null ? "" : nullToEmpty(request.command()) + " " + nullToEmpty(request.goal())))
                .toLowerCase();
        if (text.contains("test") || text.contains("测试") || text.contains("fail") || text.contains("失败")) {
            return definitions.stream()
                    .filter(definition -> "code_refix".equalsIgnoreCase(definition.domain())
                            && "fix_failing_test".equalsIgnoreCase(definition.scenario()))
                    .findFirst()
                    .orElse(definitions.get(0));
        }
        return definitions.get(0);
    }

    private Optional<SuperpowerDefinition> toDefinition(SkillEntity row) {
        String content = row.getSkillContent();
        SkillManifest manifest = parseManifest(row);
        if (manifest == null || manifest.getSuperpower() == null) {
            return Optional.empty();
        }
        SkillManifest.SuperpowerBinding cfg = manifest.getSuperpower();
        if (!StringUtils.hasText(cfg.getDomain()) || !StringUtils.hasText(cfg.getScenario())) {
            log.debug("Skipping superpower skill {}: domain/scenario missing", row.getName());
            return Optional.empty();
        }

        String body = "";
        try {
            body = frontmatterParser.parse(content).getBody();
        } catch (Exception ignored) {
            body = content == null ? "" : content;
        }

        return Optional.of(new SuperpowerDefinition(
                row.getId(),
                row.getName(),
                row.getDescription(),
                row.getVersion(),
                Boolean.TRUE.equals(row.getBuiltin()),
                row.getWorkspaceId(),
                cfg,
                content,
                body
        ));
    }

    private SkillManifest parseManifest(SkillEntity row) {
        try {
            SkillManifest parsedFromContent = manifestParser.parse(row.getSkillContent());
            if (parsedFromContent != null && parsedFromContent.getSuperpower() != null) {
                return parsedFromContent;
            }
        } catch (Exception e) {
            log.debug("Failed to parse SKILL.md manifest for skill {}: {}", row.getName(), e.getMessage());
        }
        if (StringUtils.hasText(row.getManifestJson())) {
            try {
                return objectMapper.readValue(row.getManifestJson(), SkillManifest.class);
            } catch (Exception e) {
                log.debug("Failed to parse manifest_json for skill {}: {}", row.getName(), e.getMessage());
            }
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        String lower = value.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
