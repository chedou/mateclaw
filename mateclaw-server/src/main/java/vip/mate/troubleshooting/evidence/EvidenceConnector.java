package vip.mate.troubleshooting.evidence;

import java.util.List;

public interface EvidenceConnector {

    boolean supports(String evidenceType);

    List<CollectedEvidence> collect(EvidenceCollectionRequest request);

    default int order() {
        return 100;
    }

    default String id() {
        return getClass().getSimpleName();
    }
}
