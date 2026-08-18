package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;

/**
 * Immutable server-owned identity frozen before a formal diagnosis can touch
 * an external evidence source.
 */
public record FormalDiagnosisAdmission(
        int pilotPlanVersion,
        PlaybookVersionRef playbookVersionRef,
        SopEntry playbook,
        KnowledgeEvidenceGrade knowledgeEvidenceGrade,
        EvidenceSpinePlan evidenceSpinePlan,
        String guanceAcceptanceId,
        String guanceBindingFingerprint) {

    public FormalDiagnosisAdmission {
        if (pilotPlanVersion < 1
                || playbookVersionRef == null
                || playbook == null
                || evidenceSpinePlan == null
                || guanceAcceptanceId == null
                || guanceAcceptanceId.isBlank()
                || guanceBindingFingerprint == null
                || !guanceBindingFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "formal diagnosis admission identity is incomplete");
        }
        knowledgeEvidenceGrade = knowledgeEvidenceGrade == null
                ? KnowledgeEvidenceGrade.UNVERIFIED
                : knowledgeEvidenceGrade;
        guanceAcceptanceId = guanceAcceptanceId.trim();
        guanceBindingFingerprint = guanceBindingFingerprint.trim();
    }
}
