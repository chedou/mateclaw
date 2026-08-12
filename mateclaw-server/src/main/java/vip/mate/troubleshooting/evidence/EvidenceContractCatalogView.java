package vip.mate.troubleshooting.evidence;

import java.util.List;

/** Safe catalog row for one evidence method (contract). Query template is admin-only. */
public record EvidenceContractCatalogView(
        long workspaceId,
        List<EvidenceContractView> contracts) {

    public EvidenceContractCatalogView {
        contracts = List.copyOf(contracts == null ? List.of() : contracts);
    }

    public record EvidenceContractView(
            String contractRef,
            String signalKind,
            String scopeType,
            String system,
            String service,
            String scenario,
            String question,
            String summary,
            String namespace,
            int maxRows,
            List<String> fixedConditions,
            List<String> requiredAssetParameters,
            String origin,
            boolean enabled,
            int version,
            /** Present only on admin detail/edit responses. */
            String queryTemplate) {

        public EvidenceContractView {
            fixedConditions = List.copyOf(fixedConditions == null ? List.of() : fixedConditions);
            requiredAssetParameters = List.copyOf(
                    requiredAssetParameters == null ? List.of() : requiredAssetParameters);
        }

        public EvidenceContractView withoutQueryTemplate() {
            return new EvidenceContractView(
                    contractRef, signalKind, scopeType, system, service, scenario, question,
                    summary, namespace, maxRows, fixedConditions, requiredAssetParameters,
                    origin, enabled, version, null);
        }
    }
}
