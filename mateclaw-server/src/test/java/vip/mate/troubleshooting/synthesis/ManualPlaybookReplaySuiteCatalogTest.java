package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManualPlaybookReplaySuiteCatalogTest {

    @Test
    void loadsTheBundledTopologySuiteWithAnExactStableFingerprint() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplayFingerprint fingerprints =
                new ManualPlaybookReplayFingerprint(objectMapper);
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        fingerprints,
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved = catalog.find(
                        "csdp:scenario:deployment_topology_probe")
                .orElseThrow();

        assertThat(resolved.fingerprint()).matches("[a-f0-9]{64}");
        assertThat(resolved.suite().suiteId())
                .isEqualTo("deployment-topology-probe/v1");
        assertThat(resolved.suite().exampleCandidate().sopId())
                .isEqualTo("manual-deployment-topology-probe-v1");
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::caseId)
                .containsExactly(
                        "failed-probe-positive",
                        "healthy-probe-negative",
                        "probe-unavailable-abstain");
        assertThat(catalog.find("csdp:scenario:unknown")).isEmpty();
    }

    /**
     * The blueprint's first scenario (§11.1) is a no-error-code fault, so the
     * online lane can only reach it through a SCENARIO Playbook. A quarantined
     * seed is warned about and skipped, which looks identical to "nobody
     * registered it" — hence an explicit assertion that it actually resolved.
     */
    @Test
    void theFirstScenarioResolvesAsASceneratioSuiteWithTheThreeStepSpine() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved =
                catalog.find("csdp:scenario:message_send_failed").orElseThrow();

        assertThat(resolved.suite().exampleCandidate().evidenceRequests())
                .extracting(request -> request.signalKind())
                .as("无码路的证据计划就是那三步脊柱")
                .containsExactly("log_search", "log_trace_bundle", "contrast_sample");
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                .as("录制正例 + 服务端生成的排除例与弃权例")
                .containsExactlyInAnyOrder(
                        ManualPlaybookReplaySuite.Disposition.MATCHED,
                        ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                        ManualPlaybookReplaySuite.Disposition.ABSTAINED);
        assertThat(resolved.suite().exampleCandidate().actions())
                .allSatisfy(action -> assertThat(action.actionType())
                        .as("场景 Playbook 不得携带手写的生产写动作")
                        .isNotEqualTo(ActionType.MANUAL_WRITE));
    }

    @Test
    void theCtiCreateConversationScenarioIsARecordedThreeStepPlaybook() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved = catalog.find(
                        "csdp:scenario:cti_create_conversation_failed")
                .orElseThrow();
        SopEntry candidate = resolved.suite().exampleCandidate();

        assertThat(candidate.service()).isEqualTo("csdp-task");
        assertThat(candidate.evidenceRequests())
                .extracting(request -> request.signalKind())
                .containsExactly("log_search", "log_trace_bundle", "contrast_sample");
        assertThat(candidate.diagnosisRules()).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.confidence().name()).isEqualTo("LOW");
                    assertThat(rule.rootCause()).contains("CTI", "会话创建", "701018");
                    assertThat(rule.summary())
                            .contains("701018", "不声称已证明 701022")
                            .doesNotContain("下游具体组件根因已确认");
                });
        assertThat(resolved.evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                .containsExactly(
                        ManualPlaybookReplaySuite.Disposition.MATCHED,
                        ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                        ManualPlaybookReplaySuite.Disposition.ABSTAINED);
    }

    @Test
    void theItgw904003RouteUsesRecordedComparisonEvidenceForALocatedConclusion() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved =
                catalog.find("csdp:904003").orElseThrow();
        SopEntry candidate = resolved.suite().exampleCandidate();

        assertThat(candidate.service()).isEqualTo("csdp-wechat");
        assertThat(candidate.evidenceRequests())
                .extracting(request -> request.signalKind())
                .containsExactly("log_search", "log_trace_bundle", "contrast_sample");
        assertThat(candidate.diagnosisRules()).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.confidence().name()).isEqualTo("HIGH");
                    assertThat(rule.conclusionType().name()).isEqualTo("LOCATED");
                    assertThat(rule.rootCause()).contains("ITGW", "内容安全策略", "拦截");
                    assertThat(rule.requiredSignals())
                            .containsExactly(
                                    "itgw_access_failure_present",
                                    "itgw_content_policy_discriminated");
                });
        assertThat(resolved.evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                .containsExactly(
                        ManualPlaybookReplaySuite.Disposition.MATCHED,
                        ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                        ManualPlaybookReplaySuite.Disposition.ABSTAINED);
        ManualPlaybookReplaySuite.ReplayCase weakFailure = resolved.suite().cases().stream()
                .filter(item -> item.expectedDisposition()
                        == ManualPlaybookReplaySuite.Disposition.EXCLUDED)
                .findFirst()
                .orElseThrow();
        assertThat(weakFailure.evidence().stream()
                .filter(item -> "ITGW-CONTRAST".equals(item.requestId()))
                .findFirst()
                .orElseThrow()
                .observed())
                .as("失败仅 1/100 即使成功 0/100，也不能得到 LOCATED/HIGH")
                .containsEntry("failure_sample_count", 100D)
                .containsEntry("failure_match_count", 1D)
                .containsEntry("success_sample_count", 100D)
                .containsEntry("success_match_count", 0D);
    }

    /**
     * The 903001 fixture is the only Playbook carrying a production-write
     * action, and that is now its job.
     *
     * <p>Until it did, the product's central guarantee — 人工批准只推进状态机，
     * 不触发执行 — could only be demonstrated in its refusing half
     * ({@code POST /execute} answers 409). The affirmative half, that approval
     * moves the action to {@code APPROVED_NOT_EXECUTED} while execution stays
     * {@code BLOCKED}, had no Playbook to walk it on.</p>
     *
     * <p>Dropping this action would not break any unit test; the scenario smoke
     * would fail at runtime with no build-time explanation. Hence this test.</p>
     */
    @Test
    void theFixturePlaybookCarriesTheProductionWriteActionTheScenarioNeeds() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        List<RecommendedAction> fixtureActions = catalog.find("csdp:903001")
                .orElseThrow()
                .suite()
                .exampleCandidate()
                .actions();

        assertThat(fixtureActions)
                .as("903001 是夹具，它必须带一个生产写动作供场景冒烟行走批准红线")
                .filteredOn(action -> action.actionType() == ActionType.MANUAL_WRITE)
                .singleElement()
                .satisfies(write -> {
                    assertThat(write.requiresApproval()).isTrue();
                    assertThat(write.approvalStatus())
                            .isEqualTo(ApprovalStatus.PENDING);
                    assertThat(write.executionStatus())
                            .as("生产写从注册那一刻起就必须是 BLOCKED")
                            .isEqualTo(ExecutionStatus.BLOCKED);
                });
    }

    /**
     * IM1010 is real-data knowledge; its actions come from the recording. A
     * production-write action must never be authored into it to make a demo
     * more interesting — that would put an invented instruction inside the one
     * Playbook whose content is meant to be evidence-derived.
     */
    @Test
    void theRealDataPlaybookCarriesNoAuthoredProductionWrite() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        assertThat(catalog.find("csdp:IM1010").orElseThrow()
                .suite().exampleCandidate().actions())
                .allSatisfy(action -> assertThat(action.actionType())
                        .isNotEqualTo(ActionType.MANUAL_WRITE));
    }

    @Test
    void generatesTheIm1010SuiteFromRecordedAggregateEvidence() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved = catalog.find("csdp:IM1010")
                .orElseThrow();

        assertThat(catalog.rejectedSeeds()).isEmpty();
        assertThat(resolved.suite().suiteId()).isEqualTo("csdp-im1010-message-send/v1");
        assertThat(resolved.suite().exampleCandidate().service()).isEqualTo("csp-rpc-msg");
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                .containsExactly(
                        ManualPlaybookReplaySuite.Disposition.MATCHED,
                        ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                        ManualPlaybookReplaySuite.Disposition.ABSTAINED);
        assertThat(resolved.suite().exampleCandidate().actions())
                .extracting(RecommendedAction::actionType)
                .allMatch(type -> type == ActionType.AUTO_READONLY
                        || type == ActionType.HUMAN_CONTACT);
    }

    @Test
    void sourceGradeComesFromTheCatalogLaneRatherThanSelectorGuessing() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        assertThat(catalog.find("csdp:IM1010").orElseThrow().evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        assertThat(catalog.find("csdp:scenario:message_send_failed")
                .orElseThrow().evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        assertThat(catalog.find("csdp:903001").orElseThrow().evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
        assertThat(catalog.find("csdp:scenario:deployment_topology_probe")
                .orElseThrow().evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
    }

    @Test
    void recordedAuthorityRequiresTheExactServerOwnedCandidateFingerprint()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));
        SopEntry exact = catalog.find("csdp:IM1010").orElseThrow()
                .suite().exampleCandidate();
        ObjectNode alteredNode = objectMapper.valueToTree(exact);
        alteredNode.put("cause", "手写替换的判据来源");
        SopEntry altered = objectMapper.treeToValue(alteredNode, SopEntry.class);

        assertThat(catalog.evidenceGrade("csdp:IM1010", exact))
                .contains(KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        assertThat(catalog.evidenceGrade("csdp:IM1010", altered))
                .as("复用 selector 但改写合同的 candidate 不能继承录制权威")
                .isEmpty();

        // 「继承不了录制权威」不等于「不许被批准」。这两件事此前共用同一个返回值：
        // 改一个字，评审面板显示可批准，点批准得到 409——产品里没有「写一条自己的
        // 知识」这条路。指纹比对是「什么成色」的正确答案，是「能不能批准」的错误答案。
        assertThat(catalog.promotionGrade("csdp:IM1010", exact))
                .isEqualTo(KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        assertThat(catalog.promotionGrade("csdp:IM1010", altered))
                .as("有套件、阈值自撰：能批，但成色只到自撰夹具")
                .isEqualTo(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
        assertThat(catalog.promotionGrade("acme:scenario:gateway_timeout", altered))
                .as("连套件都没有：没有任何东西证明过它")
                .isEqualTo(KnowledgeEvidenceGrade.UNVERIFIED);
    }

    @Test
    void quarantinesOneInvalidRecordedSeedWithoutRemovingFixedSuites() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode document;
        try (var input = new ClassPathResource(
                "troubleshooting/replay/manual-playbook-replay-suites.json")
                .getInputStream()) {
            document = (ObjectNode) objectMapper.readTree(input);
        }
        document.put("version", 2);
        ArrayNode recordedSeeds = document.putArray("recordedEvidenceSeeds");
        recordedSeeds.addObject()
                .put("contractVersion", "invalid-recorded-seed")
                .put("selectorKey", "csdp:BROKEN");

        ManualPlaybookReplayFingerprint fingerprints =
                new ManualPlaybookReplayFingerprint(objectMapper);
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        fingerprints,
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ByteArrayResource(objectMapper.writeValueAsBytes(document)));

        assertThat(catalog.find("csdp:903001")).isPresent();
        assertThat(catalog.rejectedSeeds())
                .containsExactly(new ManualPlaybookReplaySuiteCatalog.RejectedSeed(
                        "csdp:BROKEN", "INVALID_RECORDED_EVIDENCE_SEED"));
    }

    @Test
    void quarantinedSeedReferencesNeverExposeUnboundedOrSecretShapedSelectors()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode document;
        try (var input = new ClassPathResource(
                "troubleshooting/replay/manual-playbook-replay-suites.json")
                .getInputStream()) {
            document = (ObjectNode) objectMapper.readTree(input);
        }
        ArrayNode recordedSeeds = document.putArray("recordedEvidenceSeeds");
        recordedSeeds.addObject()
                .put("contractVersion", "invalid-recorded-seed")
                .put("selectorKey", "csdp:" + "X".repeat(300));
        recordedSeeds.addObject()
                .put("contractVersion", "invalid-recorded-seed")
                .put("selectorKey", "token=redaction-fixture");

        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ByteArrayResource(objectMapper.writeValueAsBytes(document)));

        assertThat(catalog.rejectedSeeds())
                .containsExactly(
                        new ManualPlaybookReplaySuiteCatalog.RejectedSeed(
                                "recordedEvidenceSeeds[0]",
                                "INVALID_RECORDED_EVIDENCE_SEED"),
                        new ManualPlaybookReplaySuiteCatalog.RejectedSeed(
                                "recordedEvidenceSeeds[1]",
                                "INVALID_RECORDED_EVIDENCE_SEED"));
    }
}
