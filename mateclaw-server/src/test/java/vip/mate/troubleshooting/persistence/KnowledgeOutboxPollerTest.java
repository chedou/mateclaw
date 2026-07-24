package vip.mate.troubleshooting.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.troubleshooting.knowledge.KnowledgeOutboxPoller;
import vip.mate.troubleshooting.knowledge.KnowledgePublicationSink;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeOutboxPollerTest {

    @Mock private TroubleshootingKnowledgeOutboxMapper mapper;
    @Mock private KnowledgePublicationSink sink;
    @Mock private ObjectProvider<KnowledgePublicationSink> sinkProvider;

    private ObjectMapper objectMapper;
    private KnowledgeOutboxPoller poller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        poller = new KnowledgeOutboxPoller(
                mapper,
                sinkProvider,
                objectMapper,
                Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneOffset.UTC),
                "test-worker");
    }

    @Test
    void absentPublisherLeavesRowsUntouched() {
        when(sinkProvider.getIfAvailable()).thenReturn(null);

        poller.publishPending();

        verify(mapper, never()).selectList(any());
    }

    @Test
    void successfulPublicationIsClaimedAndAcknowledged() throws Exception {
        TroubleshootingKnowledgeOutboxEntity row = row();
        when(sinkProvider.getIfAvailable()).thenReturn(sink);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.claim(eq(1L), eq("test-worker"), any(), any())).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.markPublished(eq(1L), eq("test-worker"), any())).thenReturn(1);

        poller.publishPending();

        verify(sink).publish(eq(7L), any(KnowledgeCandidate.class));
        verify(mapper).markPublished(eq(1L), eq("test-worker"), any());
        verify(mapper, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void publisherFailureReleasesTheLeaseForRetry() throws Exception {
        TroubleshootingKnowledgeOutboxEntity row = row();
        when(sinkProvider.getIfAvailable()).thenReturn(sink);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.claim(eq(1L), eq("test-worker"), any(), any())).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(row);
        org.mockito.Mockito.doThrow(new IllegalStateException("wiki unavailable"))
                .when(sink).publish(eq(7L), any(KnowledgeCandidate.class));

        poller.publishPending();

        verify(mapper).markFailed(eq(1L), eq("test-worker"), eq("wiki unavailable"), any());
        verify(mapper, never()).markPublished(any(), any(), any());
    }

    private TroubleshootingKnowledgeOutboxEntity row() throws Exception {
        KnowledgeCandidate candidate = new KnowledgeCandidate(
                "candidate-1",
                KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                "diag-1",
                "case-1",
                "run-1",
                "CSDP",
                "903001",
                "csdp:903001",
                "root cause",
                List.of(),
                List.of(),
                List.of(),
                "resolved",
                null,
                "on-call",
                Instant.parse("2026-07-25T02:00:00Z"));
        TroubleshootingKnowledgeOutboxEntity row = new TroubleshootingKnowledgeOutboxEntity();
        row.setId(1L);
        row.setWorkspaceId(7L);
        row.setPayloadJson(objectMapper.writeValueAsString(candidate));
        row.setContractVersion(KnowledgeCandidate.CURRENT_CONTRACT_VERSION);
        row.setStatus(KnowledgePublicationStatus.PROCESSING);
        row.setClaimedBy("test-worker");
        return row;
    }
}
