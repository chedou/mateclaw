package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;

import java.time.LocalDateTime;

/**
 * Queue row for the console list.
 *
 * <p>Deliberately built from indexed columns only, never by parsing the stored
 * aggregate: a duty queue is read constantly, and deserializing every full
 * diagnosis to render a list would make the cheapest screen the most expensive
 * one. Whoever opens a row gets the whole aggregate from the detail endpoint.</p>
 */
public record DiagnosisSummary(
        String diagnosisId,
        String caseId,
        String system,
        String errorCode,
        String service,
        String status,
        InvestigationMode investigationMode,
        RouteAuthority routeAuthority,
        RouteSemanticsProvenance routeSemanticsProvenance,
        boolean rehearsal,
        int version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public DiagnosisSummary {
        if (routeSemanticsProvenance == null) {
            throw new IllegalArgumentException(
                    "routeSemanticsProvenance must not be null");
        }
        // Prefer plain if/else over switch-on-enum: some incremental reloads
        // leave behind a missing DiagnosisSummary$1 synthetic and break the queue.
        if (routeSemanticsProvenance == RouteSemanticsProvenance.PERSISTED) {
            if (investigationMode == null || routeAuthority == null) {
                throw new IllegalArgumentException(
                        "PERSISTED diagnosis summaries require both typed route semantics");
            }
        } else if (routeSemanticsProvenance == RouteSemanticsProvenance.LEGACY_DERIVED) {
            if (investigationMode != null || routeAuthority != null) {
                throw new IllegalArgumentException(
                        "LEGACY_DERIVED diagnosis summaries must not carry typed route semantics");
            }
        }
    }

    public static DiagnosisSummary from(TroubleshootingDiagnosisEntity entity) {
        RouteSemantics semantics = routeSemantics(entity);
        return new DiagnosisSummary(
                entity.getDiagnosisId(),
                entity.getCaseId(),
                entity.getSystem(),
                entity.getErrorCode(),
                entity.getService(),
                entity.getStatus(),
                semantics.investigationMode(),
                semantics.routeAuthority(),
                semantics.provenance(),
                Boolean.TRUE.equals(entity.getRehearsal()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private static RouteSemantics routeSemantics(TroubleshootingDiagnosisEntity entity) {
        String contractVersion = entity.getContractVersion();
        String investigationMode = entity.getInvestigationMode();
        String routeAuthority = entity.getRouteAuthority();
        boolean hasInvestigationMode = investigationMode != null;
        boolean hasRouteAuthority = routeAuthority != null;

        if (isLegacyContract(contractVersion)) {
            if (!hasInvestigationMode && !hasRouteAuthority) {
                return new RouteSemantics(
                        null, null, RouteSemanticsProvenance.LEGACY_DERIVED);
            }
            throw new IllegalStateException(
                    "legacy diagnosis rows must not persist indexed route semantics");
        }

        if (contractVersion == null || contractVersion.isBlank()) {
            throw new IllegalStateException(
                    "diagnosis summary requires a contractVersion before route semantics can be read");
        }
        if (!hasInvestigationMode || !hasRouteAuthority) {
            throw new IllegalStateException(
                    "non-legacy diagnosis rows must carry both indexed route semantics");
        }
        return new RouteSemantics(
                parseInvestigationMode(investigationMode),
                parseRouteAuthority(routeAuthority),
                RouteSemanticsProvenance.PERSISTED);
    }

    private static boolean isLegacyContract(String contractVersion) {
        return "1.3".equals(contractVersion) || "1.4".equals(contractVersion);
    }

    private static InvestigationMode parseInvestigationMode(String value) {
        try {
            return InvestigationMode.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "unknown indexed investigationMode: " + value,
                    error);
        }
    }

    private static RouteAuthority parseRouteAuthority(String value) {
        try {
            return RouteAuthority.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "unknown indexed routeAuthority: " + value,
                    error);
        }
    }

    private record RouteSemantics(
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            RouteSemanticsProvenance provenance) {}
}
