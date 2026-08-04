package vip.mate.troubleshooting.evidence;

import java.util.List;

/** Assets plus the safe, reviewed contract choices that an admin may bind to them. */
public record ObservabilityAssetCatalogView(
        long workspaceId,
        List<ObservabilityAssetView> assets,
        List<ContractOption> contracts) {

    public ObservabilityAssetCatalogView {
        assets = List.copyOf(assets == null ? List.of() : assets);
        contracts = List.copyOf(contracts == null ? List.of() : contracts);
    }

    public record ContractOption(
            String contractRef,
            String signalKind,
            String scenario,
            String question,
            String summary,
            List<String> requiredAssetParameters) {

        public ContractOption {
            requiredAssetParameters = List.copyOf(
                    requiredAssetParameters == null ? List.of() : requiredAssetParameters);
        }
    }
}
