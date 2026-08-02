package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.service.ScenarioDiagnosisService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;
import java.util.List;

/**
 * Opens the online lane for a fault that has no error code.
 *
 * <p><b>Why this endpoint exists.</b> {@code POST /incidents} routes by error
 * code alone, so a report without one falls through to the miss-path Agent and,
 * when that Agent is switched off — the default — answers 409. That closed the
 * online lane for the blueprint's own first acceptance case (§11.1), which is
 * defined as a no-error-code fault. Naming a registered scenario reopens it
 * with zero LLM calls and no contract change: {@code DETERMINISTIC +
 * SCENARIO_PLAYBOOK + EXPLICIT} was always a legal shape.</p>
 *
 * <p><b>What naming a scenario does and does not buy.</b> It selects which
 * approved read-only evidence plan applies. It does not assert a cause: the
 * diagnosis starts {@code INSUFFICIENT_EVIDENCE} / {@code NEEDS_INVESTIGATION}
 * and only the evidence can move it. If picking a scenario could hand back a
 * conclusion, the route/authority split would be decorative.</p>
 *
 * <p><b>Authority is EXPLICIT, and that is a claim about the caller.</b> A human
 * chose this scenario, so the diagnosis records {@code EXPLICIT}. A model that
 * proposes a registered key must not reuse this endpoint — its diagnoses have to
 * carry {@code MODEL_PROPOSED} so the two can be told apart in the data, which
 * is the whole point of §3.1. Enforcing that split is why this endpoint requires
 * an authenticated operator rather than accepting a caller-supplied actor.</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/scenarios/{scenarioKey}/diagnoses")
@RequiredArgsConstructor
public class ScenarioDiagnosisController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final ScenarioDiagnosisService service;

    @PostMapping
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> create(
            @PathVariable String scenarioKey,
            @Valid @RequestBody ScenarioDiagnosisRequest request,
            @RequestAttribute(TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE)
                    Instant reportedAt,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.create(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                request.toIncidentContext(reportedAt),
                scenarioKey,
                request.isRehearsal(),
                currentActor(),
                reportedAt,
                List.of("场景已显式选定，只读取证尚未执行；当前 Diagnosis 不输出根因。"),
                null));
    }

    private String currentActor() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "scenario diagnosis creation requires an authenticated operator");
        }
        return authentication.getName();
    }
}
