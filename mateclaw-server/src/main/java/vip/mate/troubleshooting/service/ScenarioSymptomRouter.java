package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects an approved scenario Playbook for an alert that carries no error code.
 *
 * <p><b>Why this exists.</b> Deterministic routing required an error code, so a
 * whole class of real alerts — dial-test failures, availability probes, anything
 * a monitoring platform raises by symptom — could never reach a reviewed
 * Playbook no matter how many were registered. They fell through to
 * OPEN_DISCOVERY every time, which spends a model call to reach an abstention
 * that an approved scenario Playbook could have answered deterministically.
 *
 * <p><b>What keeps this from becoming a guess.</b> The Playbook declares the
 * phrases it answers for; this class only checks containment and counts the
 * matches. It never scores, ranks, or breaks a tie. Two matching Playbooks mean
 * the registry is ambiguous about who owns the symptom, and resolving that by
 * picking one would silently hand deterministic Playbook authority to whichever
 * row sorted first. The existing registry already refuses to route on ambiguity
 * for exactly this reason (see
 * {@link TroubleshootingSopPersistenceService#findUniqueOperationalSystem}); a
 * symptom is weaker evidence than an error code, so it cannot get a weaker rule.
 *
 * <p>An unmatched or ambiguous symptom is not an error. It returns a reason and
 * the caller falls through to the path it already used, so adding this router
 * can only convert former misses into deterministic hits.
 */
public class ScenarioSymptomRouter {

    /**
     * Scenario Playbooks are a small curated set per system, unlike the ~146
     * error codes. Bounded so a misconfigured registry cannot turn every report
     * into a long scan.
     */
    private static final int MAX_SCANNED_PLAYBOOKS = 200;

    private static final String SCENARIO_PREFIX = "scenario:";

    private final TroubleshootingSopPersistenceService playbooks;

    public ScenarioSymptomRouter(TroubleshootingSopPersistenceService playbooks) {
        this.playbooks = playbooks;
    }

    /** Either the single approved scenario Playbook that claims this symptom, or why not. */
    public record ScenarioRoute(SopEntry playbook, String missReason) {

        public boolean matched() {
            return playbook != null;
        }

        static ScenarioRoute hit(SopEntry playbook) {
            return new ScenarioRoute(playbook, null);
        }

        static ScenarioRoute miss(String reason) {
            return new ScenarioRoute(null, reason);
        }
    }

    public ScenarioRoute route(long workspaceId, IncidentContext incident) {
        if (workspaceId <= 0 || incident == null) {
            return ScenarioRoute.miss("no workspace or incident to route");
        }
        String system = trimmed(incident.system());
        // The curated symptom line, not the raw paste. Raw input carries whatever
        // the reporter copied in, so a trigger phrase quoted inside an attached
        // log would route the report somewhere nobody declared.
        String symptom = trimmed(incident.title());
        if (system.isEmpty() || symptom.isEmpty()) {
            return ScenarioRoute.miss(
                    "symptom routing needs both a system and a symptom description");
        }

        List<SopEntry> matches;
        try {
            matches = matchingPlaybooks(workspaceId, system, symptom);
        } catch (RuntimeException unavailable) {
            // Falling through to the existing miss path is the status quo, so a
            // registry hiccup degrades rather than failing the whole report.
            return ScenarioRoute.miss("the Playbook registry was unavailable for symptom routing");
        }

        if (matches.isEmpty()) {
            return ScenarioRoute.miss(
                    "no approved scenario Playbook for '" + system
                            + "' declares a trigger matching this symptom");
        }
        if (matches.size() > 1) {
            return ScenarioRoute.miss(
                    "the symptom matches " + matches.size()
                            + " approved scenario Playbooks (" + keysOf(matches)
                            + "); narrow their triggers so exactly one owns it");
        }
        return ScenarioRoute.hit(matches.get(0));
    }

    private List<SopEntry> matchingPlaybooks(
            long workspaceId, String system, String symptom) {
        List<SopEntry> matches = new ArrayList<>();
        for (SopSummary summary
                : playbooks.list(workspaceId, "approved", system, MAX_SCANNED_PLAYBOOKS)) {
            if (!summary.operational() || !scenarioScoped(summary.errorCode())) {
                continue;
            }
            SopEntry playbook = playbooks.find(
                    workspaceId, summary.system(), summary.errorCode());
            // find() re-checks operational status against the version store, so
            // a row deprecated since the listing drops out here rather than
            // becoming a route.
            if (playbook != null && playbook.coversSymptom(symptom)) {
                matches.add(playbook);
            }
        }
        return matches;
    }

    private boolean scenarioScoped(String errorCode) {
        return errorCode != null
                && errorCode.trim().toLowerCase(Locale.ROOT).startsWith(SCENARIO_PREFIX);
    }

    private String keysOf(List<SopEntry> matches) {
        return matches.stream()
                .map(SopEntry::scenarioKey)
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
