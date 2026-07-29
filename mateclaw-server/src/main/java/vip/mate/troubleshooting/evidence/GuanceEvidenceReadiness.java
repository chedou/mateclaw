package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.List;

/**
 * Secret-free readiness projection for one workspace-owned Guance asset.
 *
 * <p>The projection deliberately contains binding identifiers rather than DQL,
 * credentials, or raw evidence. It is safe for the troubleshooting developer
 * panel and does not itself perform a source query.</p>
 */
public record GuanceEvidenceReadiness(
        String system,
        String service,
        Status status,
        boolean adapterEnabled,
        boolean endpointConfigured,
        CredentialState credentialState,
        boolean uniqueAssetAuthorized,
        List<SignalReadiness> signals,
        List<String> blockers) {

    public GuanceEvidenceReadiness {
        system = system == null ? "" : system.trim();
        service = service == null ? "" : service.trim();
        status = status == null ? Status.CONFIGURATION_INCOMPLETE : status;
        credentialState = credentialState == null
                ? CredentialState.NOT_INSPECTED
                : credentialState;
        signals = List.copyOf(signals == null ? List.of() : signals);
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
    }

    public enum Status {
        DISABLED,
        CONFIGURATION_INCOMPLETE,
        UNAUTHORIZED,
        READY_FOR_VALIDATION,
        CANONICAL_SIGNALS_OBSERVED
    }

    public enum CredentialState {
        NOT_INSPECTED,
        MISSING,
        CONFIGURED
    }

    public enum SignalStatus {
        NOT_ROUTED,
        UNAUTHORIZED,
        INVALID_BINDING,
        READY_FOR_VALIDATION,
        CANONICAL_RESULT_OBSERVED
    }

    public record SignalReadiness(
            String signalKind,
            boolean routedToGuance,
            SignalStatus status,
            String bindingRef,
            Instant lastObservedAt,
            String detail) {

        public SignalReadiness {
            signalKind = signalKind == null ? "" : signalKind.trim();
            status = status == null ? SignalStatus.INVALID_BINDING : status;
            bindingRef = bindingRef == null ? "" : bindingRef.trim();
            detail = detail == null ? "" : detail.trim();
        }
    }
}
