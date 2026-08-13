package vip.mate.troubleshooting.projection;

/**
 * Configuration layers an incident's system passes through before evidence exists.
 *
 * <p>Declared in dependency order. {@code SYSTEM_IDENTITY} comes first because
 * every other layer is keyed by the system code: when the reported system name
 * cannot be a routing scope, none of the layers below it can even be declared.
 */
public enum SystemOnboardingGapKind {

    SYSTEM_IDENTITY,
    PLAYBOOK,
    OPEN_DISCOVERY_PLAN,
    EVIDENCE_ROUTE,
    OBSERVABILITY_ASSET
}
