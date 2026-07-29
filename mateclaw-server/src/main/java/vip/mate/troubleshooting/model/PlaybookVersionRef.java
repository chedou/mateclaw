package vip.mate.troubleshooting.model;

/** Immutable identity of the exact approved Playbook version used by a run. */
public record PlaybookVersionRef(String playbookId, int playbookVersion) {

    public PlaybookVersionRef {
        if (playbookId == null || playbookId.isBlank() || playbookVersion < 1) {
            throw new IllegalArgumentException("Playbook version reference is invalid");
        }
        playbookId = playbookId.trim();
    }
}
