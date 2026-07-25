package vip.mate.troubleshooting.model;

import java.time.Instant;

public record TransferRecord(
        String transferId,
        String targetTeam,
        String note,
        String actor,
        Instant transferredAt,
        TransferContextSnapshot context) {

    public TransferRecord {
        transferId = required(transferId, "transferId");
        targetTeam = required(targetTeam, "targetTeam");
        note = required(note, "note");
        actor = required(actor, "actor");
        transferredAt = transferredAt == null ? Instant.EPOCH : transferredAt;
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
