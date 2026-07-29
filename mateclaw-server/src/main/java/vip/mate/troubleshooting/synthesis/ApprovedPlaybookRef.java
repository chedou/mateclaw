package vip.mate.troubleshooting.synthesis;

/** Exact active authority observed when a reviewer starts work. */
public record ApprovedPlaybookRef(String playbookId, int playbookVersion) {

    public ApprovedPlaybookRef {
        if (playbookId == null || playbookId.isBlank() || playbookVersion < 1) {
            throw new IllegalArgumentException("approved Playbook reference is invalid");
        }
        playbookId = playbookId.trim();
    }
}
