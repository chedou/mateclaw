package vip.mate.troubleshooting.followup;

import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis;

/** One safe answer in a Diagnosis-bound follow-up conversation. */
public record DiagnosisFollowUpResult(
        String diagnosisId,
        DiagnosisFollowUpStatus status,
        DiagnosisFollowUpIntent intent,
        ConclusionType conclusionType,
        EvidenceBasis evidenceBasis,
        boolean fixtureMode,
        String answer,
        DiagnosisFollowUpRun investigationRun) {

    public DiagnosisFollowUpResult {
        if (diagnosisId == null || diagnosisId.isBlank()
                || status == null || intent == null
                || conclusionType == null
                || answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("follow-up result fields are required");
        }
        diagnosisId = diagnosisId.trim();
        answer = answer.trim();
        if (status == DiagnosisFollowUpStatus.ENDED && intent != DiagnosisFollowUpIntent.END) {
            throw new IllegalArgumentException("only END may close a follow-up conversation");
        }
        if (investigationRun != null
                && intent != DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE) {
            throw new IllegalArgumentException("only supplemental evidence creates a run");
        }
    }
}
