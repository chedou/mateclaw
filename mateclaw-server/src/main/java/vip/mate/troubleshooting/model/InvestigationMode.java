package vip.mate.troubleshooting.model;

/** How an incident is investigated; independent from why the route was selected. */
public enum InvestigationMode {
    ERROR_CODE_PLAYBOOK,
    SCENARIO_PLAYBOOK,
    OPEN_DISCOVERY
}
