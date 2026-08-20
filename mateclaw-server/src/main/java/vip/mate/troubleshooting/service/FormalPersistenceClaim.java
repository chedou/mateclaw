package vip.mate.troubleshooting.service;

import java.time.Instant;

/** Shared immutable identity required by every formal Diagnosis insert. */
public interface FormalPersistenceClaim {
    String dedupKey();

    String claimToken();

    Instant claimedAt();

    Instant expiresAt();
}
