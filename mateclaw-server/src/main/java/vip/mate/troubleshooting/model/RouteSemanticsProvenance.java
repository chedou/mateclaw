package vip.mate.troubleshooting.model;

/**
 * Distinguishes whether v4 route semantics were truly persisted or only
 * reconstructed while reading legacy contracts.
 */
public enum RouteSemanticsProvenance {
    PERSISTED,
    LEGACY_DERIVED
}
