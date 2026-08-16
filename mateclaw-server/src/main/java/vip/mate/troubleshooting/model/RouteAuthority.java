package vip.mate.troubleshooting.model;

/** Why the investigation route was selected; it never implies conclusion confidence by itself. */
public enum RouteAuthority {
    EXPLICIT,
    RULE_MATCHED,
    MODEL_PROPOSED,
    POLICY_PROPOSED
}
