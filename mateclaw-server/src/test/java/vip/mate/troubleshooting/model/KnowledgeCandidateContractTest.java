package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeCandidateContractTest {

    private static final Instant CLOSED_AT =
            Instant.parse("2026-07-30T00:30:00Z");
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void legacyV1PayloadWithoutOutcomeProofOrOwnerRemainsReadable() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(candidate(
                KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                null,
                null));
        payload.remove("outcomeProof");
        payload.remove("ownerTeam");

        KnowledgeCandidate restored =
                objectMapper.treeToValue(payload, KnowledgeCandidate.class);

        assertEquals(KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                restored.contractVersion());
        assertNull(restored.outcomeProof());
        assertNull(restored.ownerTeam());
    }

    @Test
    void currentContractRoundTripsFrozenClosureProofAndOwner() throws Exception {
        KnowledgeCandidate expected = candidate(
                KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                new KnowledgeCandidate.OutcomeProof(
                        ClosureOutcome.RECOVERED,
                        true,
                        "closer-a",
                        CLOSED_AT),
                "订单平台组");

        KnowledgeCandidate restored = objectMapper.readValue(
                objectMapper.writeValueAsString(expected),
                KnowledgeCandidate.class);

        assertEquals(expected, restored);
    }

    @Test
    void outcomeProofCannotBeDetachedFromTheClosureActorOrTime() {
        KnowledgeCandidate.OutcomeProof detached =
                new KnowledgeCandidate.OutcomeProof(
                        ClosureOutcome.RECOVERED,
                        true,
                        "another-actor",
                        CLOSED_AT);

        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                        detached,
                        "订单平台组"));
    }

    @Test
    void currentContractRequiresTheServerOwnedClosureProof() {
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                        null,
                        "订单平台组"));
    }

    @Test
    void legacyContractCannotSmuggleCurrentProofOrOwnerFields() {
        KnowledgeCandidate.OutcomeProof proof =
                new KnowledgeCandidate.OutcomeProof(
                        ClosureOutcome.RECOVERED,
                        true,
                        "closer-a",
                        CLOSED_AT);

        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                        proof,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                        null,
                        "订单平台组"));
    }

    @Test
    void unsupportedContractVersionIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate("knowledge-candidate.v99", null, null));
    }

    private KnowledgeCandidate candidate(
            String contractVersion,
            KnowledgeCandidate.OutcomeProof proof,
            String ownerTeam) {
        return new KnowledgeCandidate(
                "candidate-1",
                contractVersion,
                "diag-1",
                "case-1",
                "run-1",
                "CSDP",
                "903001",
                "csdp:903001",
                "连接池耗尽",
                List.of("LOG-1"),
                List.of(),
                List.of(),
                "人工扩容后恢复",
                null,
                "closer-a",
                CLOSED_AT,
                proof,
                ownerTeam);
    }
}
