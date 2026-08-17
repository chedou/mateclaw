package vip.mate.troubleshooting.investigation;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;

import java.util.Set;

/** Canonical semantic tool backed by the existing workspace-scoped source router. */
@Component
public final class EvidenceRouterReadOnlyTool implements ReadOnlyEvidenceTool {

    public static final String TOOL_KEY = "canonical-evidence";
    public static final String VERSION = "1";

    private final EvidenceSourceRouter router;
    private final Descriptor descriptor = new Descriptor(
            TOOL_KEY,
            VERSION,
            Capability.READ_EVIDENCE,
            Set.copyOf(CanonicalEvidenceSchema.externallyRoutableSignalKinds()));

    public EvidenceRouterReadOnlyTool(EvidenceSourceRouter router) {
        this.router = router;
    }

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public EvidenceResult collect(
            ReadOnlyToolRegistry.Context context,
            EvidenceRequest request) {
        return router.collect(
                context.workspaceId(),
                request,
                context.incident(),
                context.permittedPlatforms(),
                context.deadline());
    }
}
