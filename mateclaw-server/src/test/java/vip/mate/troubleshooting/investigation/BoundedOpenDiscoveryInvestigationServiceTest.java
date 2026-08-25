package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import vip.mate.troubleshooting.agent.TroubleshootingAgentProperties;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryPlan;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedOpenDiscoveryInvestigationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void returnsAnAuditableApplicationHypothesisWhenRuntimeEvidenceIsMissing() {
        TroubleshootingAgentProperties properties = enabledProperties();
        ReadOnlyEvidenceTool tool = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        "canonical-evidence", "1", Capability.READ_EVIDENCE,
                        Set.of("error_log_scan", "k8s_workload_health"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                if (request.signalKind().equals("error_log_scan")) {
                    return new EvidenceResult(
                            request.requestId(), "logs", "", EvidenceStatus.ANOMALY,
                            "errors", Map.of("error_count", 3), "guance", NOW);
                }
                return new EvidenceResult(
                        request.requestId(), "objects", "", EvidenceStatus.MISSING,
                        "asset not configured", Map.of(), "router:unconfigured", NOW);
            }
        };
        ReadOnlyToolRegistry registry = new ReadOnlyToolRegistry(List.of(tool), CLOCK);
        BoundedOpenDiscoveryInvestigationService service = new BoundedOpenDiscoveryInvestigationService(
                properties,
                new BoundedInvestigationPlanner(registry, new CriterionEvaluator(), CLOCK),
                new DefaultOpenDiscoveryHypothesisGraphFactory(),
                CLOCK);

        Optional<BoundedOpenDiscoveryInvestigationService.Execution> execution =
                service.investigate(1L, incident());

        assertThat(execution).isPresent();
        assertThat(execution.orElseThrow().finding().type())
                .isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(execution.orElseThrow().finding().cause())
                .isEqualTo("应用服务自身出现集中错误");
        assertThat(execution.orElseThrow().planKey())
                .isEqualTo("bounded-open-discovery-v1");
        assertThat(execution.orElseThrow().planFingerprint()).matches("[a-f0-9]{64}");
        assertThat(execution.orElseThrow().plannedSignalKinds())
                .containsExactly("error_log_scan", "k8s_workload_health");
    }

    @Test
    void staysInactiveWithoutAnExplicitServerSideSwitchAndPlatformAllowlist() {
        TroubleshootingAgentProperties properties = new TroubleshootingAgentProperties();
        BoundedOpenDiscoveryInvestigationService service = new BoundedOpenDiscoveryInvestigationService(
                properties,
                new BoundedInvestigationPlanner(
                        new ReadOnlyToolRegistry(List.of(new NeverCalledTool()), CLOCK),
                        new CriterionEvaluator(),
                        CLOCK),
                new DefaultOpenDiscoveryHypothesisGraphFactory(),
                CLOCK);

        assertThat(service.investigate(1L, incident())).isEmpty();
    }

    @Test
    void formalInvestigationNarrowsAMixedConfigurationToGuanceBeforeToolIo() {
        TroubleshootingAgentProperties properties = enabledProperties();
        properties.setBoundedInvestigationPermittedPlatforms(
                List.of("recorded-replay", "prometheus", "guance"));
        AtomicReference<Set<String>> observedPlatforms = new AtomicReference<>();
        ReadOnlyEvidenceTool tool = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        "canonical-evidence", "1", Capability.READ_EVIDENCE,
                        Set.of("error_log_scan", "k8s_workload_health"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                observedPlatforms.set(context.permittedPlatforms());
                return new EvidenceResult(
                        request.requestId(), request.signalKind(), "",
                        EvidenceStatus.MISSING, "no rows", Map.of(),
                        "guance:missing", NOW);
            }
        };
        BoundedOpenDiscoveryInvestigationService service =
                new BoundedOpenDiscoveryInvestigationService(
                        properties,
                        new BoundedInvestigationPlanner(
                                new ReadOnlyToolRegistry(List.of(tool), CLOCK),
                                new CriterionEvaluator(), CLOCK),
                        new DefaultOpenDiscoveryHypothesisGraphFactory(),
                        CLOCK);

        assertThat(service.investigateFormal(
                1L, incident(), FormalOpenDiscoveryPlan.current())).isPresent();
        assertThat(observedPlatforms.get()).containsExactly("guance");
    }

    @Test
    void formalGenericInvestigationNeverPresentsABroadDirectionAsALocatedRootCause() {
        TroubleshootingAgentProperties properties = enabledProperties();
        ReadOnlyEvidenceTool tool = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        EvidenceRouterReadOnlyTool.TOOL_KEY,
                        EvidenceRouterReadOnlyTool.VERSION,
                        Capability.READ_EVIDENCE,
                        Set.of("error_log_scan", "k8s_workload_health"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                Map<String, Object> observed = request.signalKind().equals("error_log_scan")
                        ? Map.of("error_count", 3)
                        : Map.of(
                                "pod_count", 4,
                                "container_count", 4,
                                "running_container_count", 4,
                                "unhealthy_container_count", 0,
                                "max_cpu_percent", 45,
                                "max_memory_percent", 52);
                return new EvidenceResult(
                        request.requestId(), request.signalKind(), "",
                        EvidenceStatus.ANOMALY, "canonical aggregate", observed,
                        "guance:aggregate", NOW);
            }
        };
        BoundedOpenDiscoveryInvestigationService service =
                new BoundedOpenDiscoveryInvestigationService(
                        properties,
                        new BoundedInvestigationPlanner(
                                new ReadOnlyToolRegistry(List.of(tool), CLOCK),
                                new CriterionEvaluator(), CLOCK),
                        new DefaultOpenDiscoveryHypothesisGraphFactory(), CLOCK);

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigateFormal(
                                1L, incident(), FormalOpenDiscoveryPlan.current())
                        .orElseThrow();

        assertThat(execution.outcome().stopReason())
                .isEqualTo(BoundedInvestigationPlanner.StopReason.EVIDENCE_EXHAUSTED);
        assertThat(execution.finding().type())
                .isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(execution.finding().summary())
                .contains("候选方向")
                .doesNotContain("已排除其他已登记方向");
    }

    @Test
    void formalReviewedWechatSlowRequestPlanReturnsOnePlainLanguageRootCause() {
        TroubleshootingAgentProperties properties = enabledProperties();
        properties.setBoundedInvestigationMaxIterations(1);
        properties.setBoundedInvestigationMaxToolCalls(1);
        ReadOnlyEvidenceTool tool = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        EvidenceRouterReadOnlyTool.TOOL_KEY,
                        EvidenceRouterReadOnlyTool.VERSION,
                        Capability.READ_EVIDENCE,
                        Set.of("slow_request_analysis"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                return new EvidenceResult(
                        request.requestId(), request.signalKind(), "",
                        EvidenceStatus.ANOMALY, "reviewed slow request aggregate",
                        Map.of(
                                "baseline_request_count", 20417,
                                "baseline_slow_request_count", 1,
                                "current_request_count", 19585,
                                "current_slow_request_count", 19,
                                "affected_trace_count", 19,
                                "affected_pod_count", 1,
                                "partner_user_info_slow_count", 10,
                                "timeout_error_count", 0),
                        "guance:slow-request-analysis", NOW);
            }
        };
        BoundedOpenDiscoveryInvestigationService service =
                new BoundedOpenDiscoveryInvestigationService(
                        properties,
                        new BoundedInvestigationPlanner(
                                new ReadOnlyToolRegistry(List.of(tool), CLOCK),
                                new CriterionEvaluator(), CLOCK),
                        new DefaultOpenDiscoveryHypothesisGraphFactory(), CLOCK);
        FormalOpenDiscoveryPlan plan =
                FormalOpenDiscoveryPlan.fromAcceptedCapabilities(
                        Set.of("slow_request_analysis"));

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigateFormal(1L, slowIncident(), plan).orElseThrow();

        assertThat(execution.planKey()).isEqualTo(
                BoundedOpenDiscoveryInvestigationService.CSDP_WECHAT_SLOW_REQUEST_PLAN_KEY);
        assertThat(execution.plannedSignalKinds())
                .containsExactly("slow_request_analysis");
        assertThat(execution.sourceRequestCount()).isEqualTo(1);
        assertThat(execution.finding().type()).isEqualTo(RootCauseFinding.Type.LOCATED);
        assertThat(execution.finding().cause())
                .startsWith("明确排障原因：")
                .contains("partner_user_info", "流量未增长", "未见超时");
    }

    @Test
    void similarSlowWordingOnAnotherServiceStaysOnTheGenericPlan() {
        DefaultOpenDiscoveryHypothesisGraphFactory factory =
                new DefaultOpenDiscoveryHypothesisGraphFactory();
        IncidentContext otherService = new IncidentContext(
                "incident-slow-other", "CSDP", "csdp-task", null,
                "系统突然很卡", "P2", "待确认", null, NOW,
                null, "web", IncidentCompleteness.STRUCTURED, "页面加载很慢");

        assertThat(factory.createFormal(
                        otherService, FormalOpenDiscoveryPlan.current()).nodes())
                .extracting(HypothesisGraph.Node::hypothesisId)
                .doesNotContain("csdp-wechat-partner-user-info-hotspot");
    }

    @Test
    void formalGenericInvestigationRunsOnlyTheAcceptedSafeSignal() {
        TroubleshootingAgentProperties properties = enabledProperties();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> sourceFingerprint = new AtomicReference<>();
        ReadOnlyEvidenceTool tool = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        EvidenceRouterReadOnlyTool.TOOL_KEY,
                        EvidenceRouterReadOnlyTool.VERSION,
                        Capability.READ_EVIDENCE,
                        Set.of("error_log_scan"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                calls.incrementAndGet();
                sourceFingerprint.set(context.sourceBindingFingerprint());
                return new EvidenceResult(
                        request.requestId(), request.signalKind(), "",
                        EvidenceStatus.ANOMALY, "canonical aggregate",
                        Map.of("error_count", 3), "guance:aggregate", NOW);
            }
        };
        BoundedOpenDiscoveryInvestigationService service =
                new BoundedOpenDiscoveryInvestigationService(
                        properties,
                        new BoundedInvestigationPlanner(
                                new ReadOnlyToolRegistry(List.of(tool), CLOCK),
                                new CriterionEvaluator(), CLOCK),
                        new DefaultOpenDiscoveryHypothesisGraphFactory(), CLOCK);
        FormalOpenDiscoveryPlan logOnly =
                FormalOpenDiscoveryPlan.fromAcceptedCapabilities(
                        Set.of("error_log_scan"));
        String fingerprint = "a".repeat(64);

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigateFormal(
                        1L, incident(), logOnly, fingerprint).orElseThrow();

        assertThat(execution.plannedSignalKinds())
                .containsExactly("error_log_scan");
        assertThat(execution.finding().type())
                .isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(calls).hasValue(1);
        assertThat(sourceFingerprint).hasValue(fingerprint);
    }

    @Test
    void formalInvestigationNeverTurnsCallerTextIntoReportedEvidence() {
        TroubleshootingAgentProperties properties = enabledProperties();
        ReadOnlyEvidenceTool observability = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        EvidenceRouterReadOnlyTool.TOOL_KEY,
                        EvidenceRouterReadOnlyTool.VERSION,
                        Capability.READ_EVIDENCE,
                        Set.of("error_log_scan", "k8s_workload_health"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                return new EvidenceResult(
                        request.requestId(), request.signalKind(), "",
                        EvidenceStatus.MISSING, "no rows", Map.of(),
                        "guance:missing", NOW);
            }
        };
        BoundedOpenDiscoveryInvestigationService service =
                new BoundedOpenDiscoveryInvestigationService(
                        properties,
                        new BoundedInvestigationPlanner(
                                new ReadOnlyToolRegistry(
                                        List.of(new IncidentReportReadOnlyTool(), observability),
                                        CLOCK),
                                new CriterionEvaluator(), CLOCK),
                        new DefaultOpenDiscoveryHypothesisGraphFactory(), CLOCK);
        IncidentContext callerShapedLikeReviewedReport = new IncidentContext(
                "incident-formal-reported", "CSDP", "csdp-wechat", null,
                "调用接口异常（HTTP 502 · get_icare_product_mapping）",
                "P1", "客户受影响", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigateFormal(
                        1L,
                        callerShapedLikeReviewedReport,
                        FormalOpenDiscoveryPlan.current()).orElseThrow();

        assertThat(execution.plannedSignalKinds())
                .containsExactly("error_log_scan", "k8s_workload_health");
        assertThat(execution.evidence())
                .allMatch(result -> result.source().startsWith("guance"));
    }

    @Test
    void preservesTheReviewedIcare502AlertAsReportedEvidenceBeforeCheckingObservability() {
        TroubleshootingAgentProperties properties = enabledProperties();
        ReadOnlyEvidenceTool observability = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        "canonical-evidence", "1", Capability.READ_EVIDENCE,
                        Set.of("external_api_http_failure", "error_log_scan",
                                "k8s_workload_health"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                return new EvidenceResult(
                        request.requestId(), "unavailable", "", EvidenceStatus.MISSING,
                        "asset not configured", Map.of(), "router:unconfigured", NOW);
            }
        };
        BoundedOpenDiscoveryInvestigationService service = new BoundedOpenDiscoveryInvestigationService(
                properties,
                new BoundedInvestigationPlanner(
                        new ReadOnlyToolRegistry(
                                List.of(new IncidentReportReadOnlyTool(), observability), CLOCK),
                        new CriterionEvaluator(), CLOCK),
                new DefaultOpenDiscoveryHypothesisGraphFactory(),
                CLOCK);
        IncidentContext alert = new IncidentContext(
                "incident-502", "CSDP", "csdp-wechat", null,
                "调用接口异常（HTTP 502 · get_icare_product_mapping）",
                "P1", "客户受影响", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigate(1L, alert).orElseThrow();

        assertThat(execution.finding().type()).isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(execution.finding().cause())
                .isEqualTo("直接失败点：iCare 产品映射外部接口返回 HTTP 502（上游为何返回 502 尚未定位）");
        assertThat(execution.plannedSignalKinds())
                .containsExactly(
                        "incident_reported_external_http_failure",
                        "k8s_workload_health");
        assertThat(execution.evidence().getFirst().queryId())
                .isEqualTo("open-discovery-icare-product-mapping-reported");
        assertThat(execution.evidence().getFirst().source())
                .isEqualTo("incident-report:normalized");
    }

    @Test
    void doesNotAddTheNarrowIcareQuestionForOtherServicesOrIncompleteTitles() {
        DefaultOpenDiscoveryHypothesisGraphFactory factory =
                new DefaultOpenDiscoveryHypothesisGraphFactory();
        IncidentContext other = new IncidentContext(
                "incident-other", "CSDP", "csdp-task", null,
                "调用接口异常（HTTP 502 · get_icare_product_mapping）",
                "P1", "客户受影响", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);

        assertThat(factory.create(other).nodes())
                .extracting(HypothesisGraph.Node::hypothesisId)
                .doesNotContain("icare-product-mapping-http-502");
        assertThat(factory.create(incident()).nodes())
                .extracting(HypothesisGraph.Node::hypothesisId)
                .doesNotContain("icare-product-mapping-http-502");
    }

    @Test
    void explainsTheReviewedMobileFinishPolicyWithoutCallingObservability() {
        TroubleshootingAgentProperties properties = enabledProperties();
        properties.setBoundedInvestigationEnabled(false);
        properties.setBoundedInvestigationPermittedPlatforms(List.of());
        BoundedOpenDiscoveryInvestigationService service = new BoundedOpenDiscoveryInvestigationService(
                properties,
                new BoundedInvestigationPlanner(
                        new ReadOnlyToolRegistry(
                                List.of(new IncidentReportReadOnlyTool(), new NeverCalledTool()), CLOCK),
                        new CriterionEvaluator(), CLOCK),
                new DefaultOpenDiscoveryHypothesisGraphFactory(),
                CLOCK);
        IncidentContext alert = new IncidentContext(
                "incident-mobile-finish", "CSDP", "sf-icare-openapi", null,
                ReviewedIncidentPolicy.ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE,
                "P2", "待确认", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigateReviewedIncidentReport(1L, alert).orElseThrow();

        assertThat(execution.finding().type()).isEqualTo(RootCauseFinding.Type.LOCATED);
        assertThat(execution.finding().cause())
                .isEqualTo("直接失败原因：工单关联变更单，iCare 禁止在移动端完结");
        assertThat(execution.plannedSignalKinds())
                .containsExactly("incident_reported_business_policy_rejection");
        assertThat(execution.sourceRequestCount()).isEqualTo(1);
        assertThat(execution.planKey())
                .isEqualTo(BoundedOpenDiscoveryInvestigationService.REVIEWED_REPORT_PLAN_KEY);
    }

    @Test
    void explainsTheReviewedMissingRevisitResultWithoutCallingObservability() {
        TroubleshootingAgentProperties properties = enabledProperties();
        properties.setBoundedInvestigationEnabled(false);
        properties.setBoundedInvestigationPermittedPlatforms(List.of());
        BoundedOpenDiscoveryInvestigationService service = new BoundedOpenDiscoveryInvestigationService(
                properties,
                new BoundedInvestigationPlanner(
                        new ReadOnlyToolRegistry(
                                List.of(new IncidentReportReadOnlyTool(), new NeverCalledTool()), CLOCK),
                        new CriterionEvaluator(), CLOCK),
                new DefaultOpenDiscoveryHypothesisGraphFactory(),
                CLOCK);
        IncidentContext alert = new IncidentContext(
                "incident-revisit-required", "CSDP", "sf-icare-openapi", null,
                ReviewedIncidentPolicy.ICARE_REQUIRED_REVISIT_RESULT_MISSING_TITLE,
                "P2", "待确认", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);

        BoundedOpenDiscoveryInvestigationService.Execution execution =
                service.investigateReviewedIncidentReport(1L, alert).orElseThrow();

        assertThat(execution.finding().type()).isEqualTo(RootCauseFinding.Type.LOCATED);
        assertThat(execution.finding().cause())
                .isEqualTo("明确排障原因：回访结果未填写，iCare 完结校验拒绝提交");
        assertThat(execution.plannedSignalKinds())
                .containsExactly("incident_reported_business_policy_rejection");
        assertThat(execution.sourceRequestCount()).isEqualTo(1);
    }

    @Test
    void planFingerprintChangesWhenWindowOrCriterionThresholdChanges() {
        HypothesisGraph first = graph("-15m", 1);
        HypothesisGraph changedWindow = graph("-30m", 1);
        HypothesisGraph changedThreshold = graph("-15m", 100);

        String firstFingerprint = BoundedOpenDiscoveryInvestigationService.fingerprint(
                first, Set.of("guance"), 2, 2, Duration.ofSeconds(10));

        assertThat(BoundedOpenDiscoveryInvestigationService.fingerprint(
                changedWindow, Set.of("guance"), 2, 2, Duration.ofSeconds(10)))
                .isNotEqualTo(firstFingerprint);
        assertThat(BoundedOpenDiscoveryInvestigationService.fingerprint(
                changedThreshold, Set.of("guance"), 2, 2, Duration.ofSeconds(10)))
                .isNotEqualTo(firstFingerprint);
    }

    @Test
    void springUsesTheProductionConstructorWhenTheTestClockConstructorAlsoExists() {
        TroubleshootingAgentProperties properties = enabledProperties();
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(TroubleshootingAgentProperties.class, () -> properties);
            context.registerBean(ReadOnlyEvidenceTool.class, NeverCalledTool::new);
            context.registerBean(CriterionEvaluator.class, CriterionEvaluator::new);
            context.registerBean(
                    DefaultOpenDiscoveryHypothesisGraphFactory.class,
                    DefaultOpenDiscoveryHypothesisGraphFactory::new);
            context.register(ReadOnlyToolRegistry.class);
            context.register(BoundedInvestigationPlanner.class);
            context.register(BoundedOpenDiscoveryInvestigationService.class);

            context.refresh();

            assertThat(context.getBean(ReadOnlyToolRegistry.class)).isNotNull();
            assertThat(context.getBean(BoundedOpenDiscoveryInvestigationService.class))
                    .isNotNull();
        }
    }

    private static HypothesisGraph graph(String window, double threshold) {
        EvidenceRequest request = new EvidenceRequest(
                "q-errors", "error_log_scan", "检查服务错误", Map.of(), window, true);
        AnomalyCriterion criterion = new AnomalyCriterion(
                "application-error", "q-errors", "应用错误数量",
                new Criterion.NumericGte("error_count", threshold));
        HypothesisGraph.Question question = new HypothesisGraph.Question(
                "q-errors", 100, "canonical-evidence", "1", request, criterion);
        return HypothesisGraph.of(List.of(new HypothesisGraph.Hypothesis(
                "application", "应用自身错误", 100, List.of(question))));
    }

    private static TroubleshootingAgentProperties enabledProperties() {
        TroubleshootingAgentProperties properties = new TroubleshootingAgentProperties();
        properties.setBoundedInvestigationEnabled(true);
        properties.setBoundedInvestigationMaxIterations(2);
        properties.setBoundedInvestigationMaxToolCalls(2);
        properties.setBoundedInvestigationTimeout(Duration.ofSeconds(10));
        properties.setBoundedInvestigationPermittedPlatforms(List.of("guance"));
        properties.setMaxEvidenceRequests(6);
        return properties;
    }

    private static IncidentContext incident() {
        return new IncidentContext(
                "incident-1", "CSDP", "csdp-wechat", "904003", "ITGW访问失败",
                "P1", "客户受影响", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);
    }

    private static IncidentContext slowIncident() {
        return new IncidentContext(
                "incident-wechat-slow-20260825", "CSDP", "csdp-wechat", null,
                "系统突然这么卡了", "P2", "页面加载变慢", null,
                NOW, null, "wecom", IncidentCompleteness.STRUCTURED,
                "csp-wechat 页面加载很慢");
    }

    private static final class NeverCalledTool implements ReadOnlyEvidenceTool {
        @Override
        public Descriptor descriptor() {
            return new Descriptor(
                    "canonical-evidence", "1", Capability.READ_EVIDENCE,
                    Set.of("error_log_scan"));
        }

        @Override
        public EvidenceResult collect(
                ReadOnlyToolRegistry.Context context,
                EvidenceRequest request) {
            throw new AssertionError("disabled investigation must not call a tool");
        }
    }
}
