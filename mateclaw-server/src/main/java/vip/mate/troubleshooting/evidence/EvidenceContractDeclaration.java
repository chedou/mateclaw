package vip.mate.troubleshooting.evidence;

import java.util.List;
import java.util.Map;

/** Declares the next immutable workspace evidence-contract revision. */
public record EvidenceContractDeclaration(
        String contractRef,
        String signalKind,
        String scopeType,
        String system,
        String service,
        String scenario,
        String question,
        String summary,
        String namespace,
        Integer maxRows,
        String queryTemplate,
        List<String> fixedConditions,
        List<String> requiredAssetParameters,
        Map<String, String> fieldAliases,
        boolean enabled,
        Integer expectedVersion,
        String reason) {
}
