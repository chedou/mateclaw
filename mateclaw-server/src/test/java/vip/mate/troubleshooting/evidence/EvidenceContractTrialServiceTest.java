package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceContractTrialEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceContractTrialMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceContractTrialServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");

    @Test
    void runsOneExactContractAndPersistsOnlyItsSafeAuditProjection() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("search_term", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "session-service")).thenReturn(Optional.of(
                new WorkspaceObservabilityAsset(
                        "asset-1", WORKSPACE_ID, "csdp", "session-service", "guance", true,
                        Map.of("log_search", "csdp-log-search"), Map.of(), 3)));
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        when(router.collect(eq(WORKSPACE_ID), any(), any(), eq(java.util.Set.of("guance"))))
                .thenAnswer(call -> {
                    vip.mate.troubleshooting.model.EvidenceRequest request = call.getArgument(1);
                    return new EvidenceResult(
                            request.requestId(), "csdp", "", EvidenceStatus.NORMAL,
                            "canonical evidence observed",
                            Map.of("match_count", 2L, "ps_id", "ps-safe", "sample_message", "redacted"),
                            "guance:log_search", NOW);
                });
        AtomicReference<TroubleshootingEvidenceContractTrialEntity> inserted =
                new AtomicReference<>();
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        when(mapper.insert(any(TroubleshootingEvidenceContractTrialEntity.class))).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return 1;
        });
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 25_000_000L);

        EvidenceContractTrialView result = service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "CSDP", "session-service", "csdp-log-search",
                        Map.of("search_term", "SendMsgFailed"), "-15m", NOW.minusSeconds(60)),
                "ops-admin");

        assertThat(result.status()).isEqualTo(EvidenceContractTrialView.Status.OBSERVED);
        assertThat(result.assetId()).isEqualTo("asset-1");
        assertThat(result.assetVersion()).isEqualTo(3);
        assertThat(result.canonicalFields())
                .containsExactly("match_count", "ps_id", "sample_message");
        assertThat(result.durationMs()).isEqualTo(0L);
        assertThat(result.actor()).isEqualTo("ops-admin");
        assertThat(inserted.get().getObservedFields())
                .isEqualTo("[\"match_count\",\"ps_id\",\"sample_message\"]");
        assertThat(inserted.get().toString())
                .doesNotContain("SendMsgFailed", "ps-safe", "redacted");
    }

    @Test
    void trialsAnAllowedGenericContractThroughTheSystemAssetForTheRuntimeService() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(
                "system-scope", contract("error_log_scan", List.of())));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "csp-service"))
                .thenReturn(Optional.empty());
        when(assets.findSystem(WORKSPACE_ID, "csdp")).thenReturn(Optional.of(
                new WorkspaceObservabilityAsset(
                        "asset-system", WORKSPACE_ID, "csdp", "system-scope",
                        "guance", true,
                        Map.of("error_log_scan", "guance-service-error-scan"),
                        Map.of(), 1)));
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        AtomicReference<vip.mate.troubleshooting.model.IncidentContext> routedIncident =
                new AtomicReference<>();
        when(router.collect(eq(WORKSPACE_ID), any(), any(), eq(java.util.Set.of("guance"))))
                .thenAnswer(call -> {
                    vip.mate.troubleshooting.model.EvidenceRequest request = call.getArgument(1);
                    routedIncident.set(call.getArgument(2));
                    return new EvidenceResult(
                            request.requestId(), "csdp", "", EvidenceStatus.NORMAL,
                            "canonical evidence observed",
                            Map.of("error_count", 3L, "affected_trace_count", 2L,
                                    "latest_trace_id", "trace-safe"),
                            "guance:error_log_scan", NOW);
                });
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        when(mapper.insert(any(TroubleshootingEvidenceContractTrialEntity.class)))
                .thenReturn(1);
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime);

        EvidenceContractTrialView result = service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "CSDP", "csp-service", "guance-service-error-scan",
                        Map.of(), "-15m", NOW),
                "ops-admin");

        assertThat(result.status()).isEqualTo(EvidenceContractTrialView.Status.OBSERVED);
        assertThat(result.assetId()).isEqualTo("asset-system");
        assertThat(routedIncident.get().service()).isEqualTo("csp-service");
    }

    @Test
    void refusesToLetTheBrowserSupplyAParameterOwnedByPreviousEvidence() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_trace_bundle",
                List.of(parameter("ps_id", "PREVIOUS_EVIDENCE", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime);

        assertThatThrownBy(() -> service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-trace",
                        Map.of("ps_id", "browser-guessed"), "-15m", NOW),
                "ops-admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("previous evidence");

        verify(router, never()).collect(anyLong(), any(), any(), any());
        verify(mapper, never()).insert(any(TroubleshootingEvidenceContractTrialEntity.class));
    }

    @Test
    void explainsThatADeploymentFallbackMustBeRegisteredAsAWorkspaceAssetBeforeTrial() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("search_term", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "session-service"))
                .thenReturn(Optional.empty());
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime);

        assertThatThrownBy(() -> service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-search",
                        Map.of("search_term", "safe-key"), "-15m", NOW),
                "ops-admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("workspace system asset must be registered");

        verify(router, never()).collect(anyLong(), any(), any(), any());
        verify(mapper, never()).insert(any(TroubleshootingEvidenceContractTrialEntity.class));
    }

    @Test
    void listsOnlyThePersistedSafeProjectionAndDecodesCanonicalFieldNames() {
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        TroubleshootingEvidenceContractTrialEntity row =
                new TroubleshootingEvidenceContractTrialEntity();
        row.setTrialId("trial-safe");
        row.setWorkspaceId(WORKSPACE_ID);
        row.setSystem("csdp");
        row.setService("session-service");
        row.setContractRef("csdp-log-search");
        row.setSignalKind("log_search");
        row.setAssetId("asset-1");
        row.setAssetVersion(3);
        row.setStatus("OBSERVED");
        row.setStopReason("COMPLETED");
        row.setSourcePlatform("guance");
        row.setObservedFields("[\"match_count\",\"ps_id\"]");
        row.setDurationMs(42L);
        row.setActor("ops-admin");
        row.setCompletedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(mapper.selectList(any())).thenReturn(List.of(row));
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                mock(EvidenceQueryCatalogService.class),
                mock(ObservabilityAssetService.class),
                mock(EvidenceSourceRouter.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime);

        List<EvidenceContractTrialView> result = service.list(
                WORKSPACE_ID, "CSDP", "session-service", "csdp-log-search", 20);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.trialId()).isEqualTo("trial-safe");
            assertThat(view.canonicalFields()).containsExactly("match_count", "ps_id");
            assertThat(view.warning()).contains("T7/T8");
        });
    }

    @Test
    void freezesASecretFreeFailureReasonWhenTheSourceQueryThrows() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("search_term", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "session-service")).thenReturn(Optional.of(
                new WorkspaceObservabilityAsset(
                        "asset-1", WORKSPACE_ID, "csdp", "session-service", "guance", true,
                        Map.of("log_search", "csdp-log-search"), Map.of(), 3)));
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        doThrow(new IllegalStateException("upstream included raw secret value"))
                .when(router).collect(eq(WORKSPACE_ID), any(), any(), eq(java.util.Set.of("guance")));
        AtomicReference<TroubleshootingEvidenceContractTrialEntity> inserted =
                new AtomicReference<>();
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        when(mapper.insert(any(TroubleshootingEvidenceContractTrialEntity.class))).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return 1;
        });
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 25_000_000L);

        assertThatThrownBy(() -> service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-search",
                        Map.of("search_term", "SendMsgFailed"), "-15m", NOW),
                "ops-admin"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(inserted.get().getStatus()).isEqualTo("FAILED");
        assertThat(inserted.get().getStopReason()).isEqualTo("SOURCE_QUERY_FAILED");
        assertThat(inserted.get().toString()).doesNotContain("raw secret value", "SendMsgFailed");
    }

    @Test
    void rejectsAnUnboundedSourceWindowBeforeAnyAdapterCall() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("search_term", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime);

        assertThatThrownBy(() -> service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-search",
                        Map.of("search_term", "SendMsgFailed"), "-25h", NOW),
                "ops-admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("24 hours");

        verify(router, never()).collect(anyLong(), any(), any(), any());
    }

    @Test
    void recordsAnAdapterFailureSwallowedByTheRouterAsFailedInsteadOfNoEvidence() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("search_term", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "session-service")).thenReturn(Optional.of(
                new WorkspaceObservabilityAsset(
                        "asset-1", WORKSPACE_ID, "csdp", "session-service", "guance", true,
                        Map.of("log_search", "csdp-log-search"), Map.of(), 3)));
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(Map.of(
                "csdp", Map.of("log_search", List.of("guance"))));
        EvidenceSourceAdapter failingGuance = new EvidenceSourceAdapter() {
            @Override
            public String platform() {
                return "guance";
            }

            @Override
            public boolean supports(String signalKind) {
                return "log_search".equals(signalKind);
            }

            @Override
            public EvidenceResult collect(
                    long workspaceId,
                    vip.mate.troubleshooting.model.EvidenceRequest request,
                    vip.mate.troubleshooting.model.IncidentContext incident) {
                throw new IllegalStateException("upstream request failed with sensitive detail");
            }

            @Override
            public EvidenceSourceHealth health() {
                return new EvidenceSourceHealth(
                        "guance", EvidenceSourceHealth.Status.READY, false, "configured");
            }
        };
        EvidenceSourceRouter router = new EvidenceSourceRouter(
                List.of(failingGuance), properties, Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicReference<TroubleshootingEvidenceContractTrialEntity> inserted =
                new AtomicReference<>();
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        when(mapper.insert(any(TroubleshootingEvidenceContractTrialEntity.class))).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return 1;
        });
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 25_000_000L);

        EvidenceContractTrialView result = service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-search",
                        Map.of("search_term", "SendMsgFailed"), "-15m", NOW),
                "ops-admin");

        assertThat(result.status()).isEqualTo(EvidenceContractTrialView.Status.FAILED);
        assertThat(result.stopReason()).isEqualTo("SOURCE_QUERY_FAILED");
        assertThat(result.source()).isEqualTo("router");
        assertThat(inserted.get().toString())
                .doesNotContain("sensitive detail", "SendMsgFailed");
    }

    @Test
    void recordsACompletedSourceQueryWithoutCanonicalRowsAsNoEvidence() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("search_term", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "session-service")).thenReturn(Optional.of(
                new WorkspaceObservabilityAsset(
                        "asset-1", WORKSPACE_ID, "csdp", "session-service", "guance", true,
                        Map.of("log_search", "csdp-log-search"), Map.of(), 3)));
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        when(router.collect(eq(WORKSPACE_ID), any(), any(), eq(java.util.Set.of("guance"))))
                .thenAnswer(call -> {
                    vip.mate.troubleshooting.model.EvidenceRequest request = call.getArgument(1);
                    return new EvidenceResult(
                            request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                            "Guance returned no canonical evidence rows", Map.of(),
                            "guance:no_canonical_evidence", NOW);
                });
        AtomicReference<TroubleshootingEvidenceContractTrialEntity> inserted =
                new AtomicReference<>();
        TroubleshootingEvidenceContractTrialMapper mapper =
                mock(TroubleshootingEvidenceContractTrialMapper.class);
        when(mapper.insert(any(TroubleshootingEvidenceContractTrialEntity.class))).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return 1;
        });
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 25_000_000L);

        EvidenceContractTrialView result = service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-search",
                        Map.of("search_term", "SendMsgFailed"), "-24h", NOW),
                "ops-admin");

        assertThat(result.status()).isEqualTo(EvidenceContractTrialView.Status.NO_EVIDENCE);
        assertThat(result.stopReason()).isEqualTo("NO_CANONICAL_EVIDENCE");
        assertThat(result.source()).isEqualTo("guance");
        assertThat(inserted.get().getSourcePlatform()).isEqualTo("guance");
    }

    @Test
    void refusesAResourceScopeMistakenlyDeclaredAsBrowserOwned() {
        EvidenceQueryCatalogService catalog = mock(EvidenceQueryCatalogService.class);
        when(catalog.inspect(WORKSPACE_ID)).thenReturn(catalog(contract(
                "log_search",
                List.of(parameter("deployment", "EVIDENCE_REQUEST_TARGET", true)))));
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(assets.find(WORKSPACE_ID, "csdp", "session-service")).thenReturn(Optional.of(
                new WorkspaceObservabilityAsset(
                        "asset-1", WORKSPACE_ID, "csdp", "session-service", "guance", true,
                        Map.of("log_search", "csdp-log-search"), Map.of(), 3)));
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        EvidenceContractTrialService service = new EvidenceContractTrialService(
                catalog, assets, router, mock(TroubleshootingEvidenceContractTrialMapper.class),
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime);

        assertThatThrownBy(() -> service.run(
                WORKSPACE_ID,
                new EvidenceContractTrialRequest(
                        "csdp", "session-service", "csdp-log-search",
                        Map.of("deployment", "another-system"), "-15m", NOW),
                "ops-admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not browser-owned");

        verify(router, never()).collect(anyLong(), any(), any(), any());
    }

    private EvidenceQueryCatalogView catalog(EvidenceQueryCatalogView.ContractView contract) {
        return catalog("session-service", contract);
    }

    private EvidenceQueryCatalogView catalog(
            String service,
            EvidenceQueryCatalogView.ContractView contract) {
        return new EvidenceQueryCatalogView(
                "evidence-query-catalog.v1", WORKSPACE_ID, List.of(),
                List.of(new EvidenceQueryCatalogView.SystemView(
                        "csdp",
                        List.of(new EvidenceQueryCatalogView.ModuleView(
                                service, "READY", 1, List.of(), null,
                                List.of(contract))))));
    }

    private EvidenceQueryCatalogView.ContractView contract(
            String signalKind,
            List<EvidenceQueryCatalogView.ParameterView> parameters) {
        String contractRef = switch (signalKind) {
            case "log_search" -> "csdp-log-search";
            case "error_log_scan" -> "guance-service-error-scan";
            default -> "csdp-log-trace";
        };
        return new EvidenceQueryCatalogView.ContractView(
                contractRef,
                signalKind,
                "会话消息发送失败",
                "发生了什么？",
                "只读试跑",
                "guance",
                "csdp",
                List.of(),
                new EvidenceQueryCatalogView.EndpointView(
                        "DF_QUERY_DATA_V1", "POST", "/api/v1/df/query_data_v1", "dql"),
                parameters,
                signalKind.equals("log_search")
                        ? List.of("match_count", "ps_id", "sample_message")
                        : List.of("ps_id", "entries"),
                new EvidenceQueryCatalogView.BudgetView(
                        1, 20, 20, 10_000, null, null, null, null, null, null),
                new EvidenceQueryCatalogView.RouteView(
                        "WORKSPACE", List.of("guance"), false, null, null, null),
                new EvidenceQueryCatalogView.BindingView(
                        "READY_FOR_VALIDATION",
                        contractRef,
                        null, "ready"),
                true,
                List.of());
    }

    private EvidenceQueryCatalogView.ParameterView parameter(
            String name, String source, boolean required) {
        return new EvidenceQueryCatalogView.ParameterView(name, source, required, "test parameter");
    }
}
