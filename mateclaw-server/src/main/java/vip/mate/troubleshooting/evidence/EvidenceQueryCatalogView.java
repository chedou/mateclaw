package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.List;

/**
 * Secret-free catalog of the reviewed query contracts available to one workspace.
 *
 * <p>The catalog explains configuration; it does not execute evidence queries and
 * deliberately omits endpoint hosts, credentials, raw DQL and raw source rows.</p>
 */
public record EvidenceQueryCatalogView(
        String contractVersion,
        long workspaceId,
        List<SourceView> sources,
        List<SystemView> systems) {

    public EvidenceQueryCatalogView {
        contractVersion = text(contractVersion);
        sources = List.copyOf(sources == null ? List.of() : sources);
        systems = List.copyOf(systems == null ? List.of() : systems);
    }

    public record SourceView(
            String platform,
            String status,
            boolean verified,
            String endpointStatus,
            String credentialStatus,
            List<String> supportedSignals,
            String detail) {
        public SourceView {
            platform = text(platform);
            status = text(status);
            endpointStatus = text(endpointStatus);
            credentialStatus = text(credentialStatus);
            supportedSignals = List.copyOf(
                    supportedSignals == null ? List.of() : supportedSignals);
            detail = text(detail);
        }
    }

    public record SystemView(String system, List<ModuleView> modules) {
        public SystemView {
            system = text(system);
            modules = List.copyOf(modules == null ? List.of() : modules);
        }
    }

    public record ModuleView(
            String service,
            String status,
            int runnableContracts,
            List<String> blockers,
            AcceptanceView acceptance,
            List<ContractView> contracts) {
        public ModuleView {
            service = text(service);
            status = text(status);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
            acceptance = acceptance == null
                    ? new AcceptanceView("UNAVAILABLE", null, null, null, List.of())
                    : acceptance;
            contracts = List.copyOf(contracts == null ? List.of() : contracts);
        }
    }

    public record AcceptanceView(
            String status,
            String currentBindingFingerprint,
            String acceptedBy,
            Instant acceptedAt,
            List<String> blockers) {
        public AcceptanceView {
            status = text(status);
            currentBindingFingerprint = nullableText(currentBindingFingerprint);
            acceptedBy = nullableText(acceptedBy);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
        }
    }

    public record ContractView(
            String contractRef,
            String signalKind,
            String scenario,
            String question,
            String summary,
            String adapter,
            String namespace,
            List<String> fixedConditions,
            EndpointView endpoint,
            List<ParameterView> parameters,
            List<String> canonicalOutputs,
            BudgetView budget,
            RouteView route,
            BindingView binding,
            boolean runnable,
            List<String> blockers) {
        public ContractView {
            contractRef = text(contractRef);
            signalKind = text(signalKind);
            scenario = text(scenario);
            question = text(question);
            summary = text(summary);
            adapter = text(adapter);
            namespace = text(namespace);
            fixedConditions = List.copyOf(
                    fixedConditions == null ? List.of() : fixedConditions);
            parameters = List.copyOf(parameters == null ? List.of() : parameters);
            canonicalOutputs = List.copyOf(
                    canonicalOutputs == null ? List.of() : canonicalOutputs);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
        }
    }

    public record EndpointView(
            String operationKind,
            String method,
            String path,
            String qtype) {
        public EndpointView {
            operationKind = text(operationKind);
            method = text(method);
            path = text(path);
            qtype = text(qtype);
        }
    }

    public record ParameterView(
            String name,
            String source,
            boolean required,
            String description) {
        public ParameterView {
            name = text(name);
            source = text(source);
            description = text(description);
        }
    }

    public record BudgetView(
            int queryCount,
            int maxRows,
            int requestLimit,
            long timeoutMs,
            Integer maxPointCount,
            Integer intervalSeconds,
            Integer seriesLimit,
            Boolean alignTime,
            Boolean disableSampling,
            String timeZone) {
        public BudgetView {
            timeZone = nullableText(timeZone);
        }
    }

    public record RouteView(
            String origin,
            List<String> platforms,
            boolean explicitlyDisabled,
            String updatedBy,
            String reason,
            Instant updatedAt) {
        public RouteView {
            origin = text(origin);
            platforms = List.copyOf(platforms == null ? List.of() : platforms);
            updatedBy = nullableText(updatedBy);
            reason = nullableText(reason);
        }
    }

    public record BindingView(
            String status,
            String bindingRef,
            Instant lastObservedAt,
            String detail) {
        public BindingView {
            status = text(status);
            bindingRef = text(bindingRef);
            detail = text(detail);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullableText(String value) {
        String normalized = text(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
