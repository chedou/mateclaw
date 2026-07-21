package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

public record EvidenceCollectionRequest(
        long workspaceId,
        String caseId,
        TroubleshootingSopRunEntity run,
        SopDefinition sop,
        SopRouteRequest alert,
        String evidenceType
) {
}
