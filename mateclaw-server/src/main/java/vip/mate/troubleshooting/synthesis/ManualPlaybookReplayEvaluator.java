package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure zero-LLM evaluator for an immutable candidate against a bundled suite. */
@Component
public final class ManualPlaybookReplayEvaluator {

    private final CriterionEvaluator criteria;
    private final DiagnosisRuleEvaluator rules;

    public ManualPlaybookReplayEvaluator(
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules) {
        this.criteria = criteria;
        this.rules = rules;
    }

    public ManualPlaybookReplayEvaluation evaluate(
            SopEntry candidate,
            ManualPlaybookReplaySuite suite) {
        if (candidate == null || suite == null) {
            throw new IllegalArgumentException("candidate and replay suite are required");
        }
        int positiveTotal = Math.toIntExact(suite.cases().stream()
                .filter(item -> item.cohort() == ManualPlaybookReplaySuite.Cohort.POSITIVE)
                .count());
        int negativeTotal = suite.cases().size() - positiveTotal;
        if (!compatible(candidate, suite)) {
            return new ManualPlaybookReplayEvaluation(
                    positiveTotal, 0, negativeTotal, 0,
                    List.of("SUITE_CONTRACT_MISMATCH"));
        }

        int positivePassed = 0;
        int negativePassed = 0;
        Set<String> failures = new LinkedHashSet<>();
        for (ManualPlaybookReplaySuite.ReplayCase replayCase : suite.cases()) {
            CaseResult actual = evaluateCase(candidate, suite, replayCase);
            boolean passed = actual.disposition() == replayCase.expectedDisposition()
                    && (replayCase.expectedDisposition()
                            != ManualPlaybookReplaySuite.Disposition.MATCHED
                            || replayCase.expectedRuleId().equals(actual.ruleId()));
            if (replayCase.cohort() == ManualPlaybookReplaySuite.Cohort.POSITIVE) {
                if (passed) {
                    positivePassed++;
                } else {
                    failures.add("POSITIVE_EXPECTATION_MISMATCH");
                }
            } else if (passed) {
                negativePassed++;
            } else {
                failures.add("NEGATIVE_OR_ABSTAIN_EXPECTATION_MISMATCH");
            }
        }
        return new ManualPlaybookReplayEvaluation(
                positiveTotal,
                positivePassed,
                negativeTotal,
                negativePassed,
                List.copyOf(failures));
    }

    private boolean compatible(SopEntry candidate, ManualPlaybookReplaySuite suite) {
        if (!suite.selectorKey().equals(candidate.routingKey())) {
            return false;
        }
        ManualPlaybookReplaySuite.RequiredEvidenceRequest required =
                suite.requiredEvidenceRequest();
        EvidenceRequest candidateRequest = candidate.evidenceRequests().stream()
                .filter(request -> required.requestId().equals(request.requestId()))
                .findFirst()
                .orElse(null);
        if (candidateRequest == null
                || !required.signalKind().equals(candidateRequest.signalKind())
                || candidateRequest.required() != required.required()
                || !required.target().equals(candidateRequest.target())) {
            return false;
        }
        Set<String> declaredRequestIds = candidate.evidenceRequests().stream()
                .map(EvidenceRequest::requestId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return suite.cases().stream().allMatch(replayCase -> replayCase.evidence().stream()
                .map(ManualPlaybookReplaySuite.ReplayEvidence::requestId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                .equals(declaredRequestIds));
    }

    private CaseResult evaluateCase(
            SopEntry candidate,
            ManualPlaybookReplaySuite suite,
            ManualPlaybookReplaySuite.ReplayCase replayCase) {
        List<EvidenceResult> evidence = new ArrayList<>();
        for (ManualPlaybookReplaySuite.ReplayEvidence item : replayCase.evidence()) {
            evidence.add(new EvidenceResult(
                    item.requestId(),
                    "MANUAL_REPLAY",
                    "",
                    item.status(),
                    "server-owned fixed replay case",
                    item.observed(),
                    "manual-playbook-replay:" + suite.suiteId(),
                    Instant.EPOCH));
        }
        Map<String, CriterionOutcome> outcomes = criteria.outcomesBySignal(
                candidate.anomalyCriteria(), evidence);
        DiagnosisRuleEvaluator.Evaluation evaluation = rules.evaluate(
                candidate.diagnosisRules(), outcomes);
        return new CaseResult(
                switch (evaluation.disposition()) {
                    case MATCHED -> ManualPlaybookReplaySuite.Disposition.MATCHED;
                    case EXCLUDED -> ManualPlaybookReplaySuite.Disposition.EXCLUDED;
                    case ABSTAINED -> ManualPlaybookReplaySuite.Disposition.ABSTAINED;
                },
                evaluation.matchedRule() == null
                        ? null
                        : evaluation.matchedRule().ruleId());
    }

    private record CaseResult(
            ManualPlaybookReplaySuite.Disposition disposition,
            String ruleId) {}
}
