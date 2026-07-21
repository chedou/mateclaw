package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.manifest.SkillManifestParser;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;
import vip.mate.troubleshooting.model.SopDefinition;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SopRegistryService {

    public static final String FALLBACK_SKILL_NAME = "systematic-debugging";

    private final SkillMapper skillMapper;
    private final SkillManifestParser manifestParser;
    private final SkillFrontmatterParser frontmatterParser;
    private final ObjectMapper objectMapper;

    public List<SopDefinition> listSops(long workspaceId) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getEnabled, true)
                        .eq(SkillEntity::getDeleted, 0)
                        .and(w -> w.eq(SkillEntity::getBuiltin, true)
                                .or().eq(SkillEntity::getWorkspaceId, workspaceId)))
                .stream()
                .map(this::toDefinition)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(SopDefinition::domain, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SopDefinition::scenario, Comparator.nullsLast(String::compareTo))
                        .thenComparing(SopDefinition::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public Optional<SopDefinition> findBySkillId(long workspaceId, Long skillId) {
        if (skillId == null) return Optional.empty();
        return listSops(workspaceId).stream()
                .filter(sop -> skillId.equals(sop.skillId()))
                .findFirst();
    }

    private Optional<SopDefinition> toDefinition(SkillEntity row) {
        String content = row.getSkillContent();
        SkillManifest manifest = parseManifest(row);
        if (manifest == null || manifest.getTroubleshooting() == null) {
            return Optional.empty();
        }
        SkillManifest.TroubleshootingBinding cfg = manifest.getTroubleshooting();
        if (isBlank(cfg.getDomain()) || isBlank(cfg.getScenario())) {
            log.debug("Skipping troubleshooting skill {}: domain/scenario missing", row.getName());
            return Optional.empty();
        }

        SkillManifest.TroubleshootingMatch match = cfg.getMatch();
        if (match == null) {
            match = SkillManifest.TroubleshootingMatch.builder().build();
        }
        List<String> requiredEvidence = cfg.getRequiredEvidence() == null ? List.of() : cfg.getRequiredEvidence();
        List<String> optionalEvidence = cfg.getOptionalEvidence() == null ? List.of() : cfg.getOptionalEvidence();
        LocalDateTime reviewDueAt = null;
        boolean expired = false;
        if (cfg.getReviewCycleDays() != null && cfg.getReviewCycleDays() > 0 && row.getUpdateTime() != null) {
            reviewDueAt = row.getUpdateTime().plusDays(cfg.getReviewCycleDays());
            expired = reviewDueAt.isBefore(LocalDateTime.now());
        }

        String body = "";
        try {
            body = frontmatterParser.parse(content).getBody();
        } catch (Exception ignored) {
            body = content == null ? "" : content;
        }

        return Optional.of(new SopDefinition(
                row.getId(),
                row.getName(),
                row.getDescription(),
                row.getVersion(),
                Boolean.TRUE.equals(row.getBuiltin()),
                row.getWorkspaceId(),
                cfg.getDomain(),
                cfg.getScenario(),
                match,
                requiredEvidence,
                optionalEvidence,
                cfg.getOutputSchema(),
                cfg.getOwner(),
                cfg.getReviewCycleDays(),
                reviewDueAt,
                expired,
                content,
                body
        ));
    }

    private SkillManifest parseManifest(SkillEntity row) {
        SkillManifest manifest = null;
        try {
            SkillManifest parsedFromContent = manifestParser.parse(row.getSkillContent());
            if (parsedFromContent != null && parsedFromContent.getTroubleshooting() != null) {
                return parsedFromContent;
            }
        } catch (Exception e) {
            log.debug("Failed to parse SKILL.md manifest for skill {}: {}", row.getName(), e.getMessage());
        }
        if (!isBlank(row.getManifestJson())) {
            try {
                manifest = objectMapper.readValue(row.getManifestJson(), SkillManifest.class);
            } catch (Exception e) {
                log.debug("Failed to parse manifest_json for skill {}: {}", row.getName(), e.getMessage());
            }
        }
        return manifest;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
