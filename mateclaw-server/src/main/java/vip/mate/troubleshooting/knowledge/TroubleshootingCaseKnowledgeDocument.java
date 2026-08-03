package vip.mate.troubleshooting.knowledge;

/** Deterministic, redacted snapshot written to the existing Wiki vector pipeline. */
public record TroubleshootingCaseKnowledgeDocument(
        String diagnosisId,
        String caseId,
        int diagnosisVersion,
        String slug,
        String title,
        String summary,
        String markdown,
        boolean authoritativeResolution) {

    public TroubleshootingCaseKnowledgeDocument {
        diagnosisId = required(diagnosisId, "diagnosisId");
        caseId = required(caseId, "caseId");
        if (diagnosisVersion < 0) {
            throw new IllegalArgumentException("diagnosisVersion must not be negative");
        }
        slug = required(slug, "slug");
        title = required(title, "title");
        summary = required(summary, "summary");
        markdown = required(markdown, "markdown");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
