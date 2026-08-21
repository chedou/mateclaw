package vip.mate.troubleshooting.followup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.DeveloperEvidenceView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceStepKind;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.NextStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.StepTone;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagnosisFollowUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:30:00Z");

    @Mock private TroubleshootingPersistenceService persistence;
    @Mock private DiagnosisExperienceProjectionService projections;
    @Mock private DiagnosisFollowUpRunStore runs;

    private DiagnosisFollowUpService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisFollowUpService(
                persistence,
                projections,
                runs,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void answersWhyFromTheStoredProjectionWithoutCallingAModelOrChangingDiagnosis() {
        stubDiagnosisAndProjection();

        DiagnosisFollowUpResult result = service.respond(
                1L, "diag-1", "为什么是这个原因？", "admin");

        assertThat(result.status()).isEqualTo(DiagnosisFollowUpStatus.ACTIVE);
        assertThat(result.intent()).isEqualTo(DiagnosisFollowUpIntent.WHY);
        assertThat(result.answer())
                .contains("内容安全策略拦截")
                .contains("失败样本 2 条都出现该特征");
        assertThat(result.investigationRun()).isNull();
        verify(persistence).get(1L, "diag-1");
        verifyNoMoreInteractions(persistence, runs);
    }

    @Test
    void answersEvidenceUnknownsAndNextStepFromTheSameSafeProjection() {
        stubDiagnosisAndProjection();

        assertThat(service.respond(1L, "diag-1", "有哪些证据？", "admin").answer())
                .contains("失败样本 2 条都出现该特征");
        assertThat(service.respond(1L, "diag-1", "还不知道什么？", "admin").answer())
                .contains("未取得上游网关响应体");
        assertThat(service.respond(1L, "diag-1", "下一步查什么？", "admin").answer())
                .contains("核对内容安全策略");
    }

    @Test
    void supplementalEvidenceCreatesAnImmutableUnverifiedRunWithoutOverwritingDiagnosis() {
        StoredDiagnosis stored = stubDiagnosisAndProjection();
        when(runs.insert(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(DiagnosisFollowUpRun.class)))
                .thenAnswer(call -> call.getArgument(1));

        DiagnosisFollowUpResult result = service.respond(
                1L,
                "diag-1",
                "补充证据：失败请求在 PC 端可以完成，token=must-not-persist",
                "admin");

        assertThat(result.intent()).isEqualTo(DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE);
        assertThat(result.investigationRun()).isNotNull();
        assertThat(result.investigationRun().disposition())
                .isEqualTo(DiagnosisFollowUpDisposition.RECORDED_NOT_VERIFIED);
        assertThat(result.answer()).contains("没有改写原结论");
        assertThat(result.conclusionType()).isEqualTo(ConclusionType.HYPOTHESIS);
        assertThat(result.evidenceBasis()).isEqualTo(EvidenceBasis.OBSERVED);
        assertThat(result.fixtureMode()).isFalse();

        ArgumentCaptor<DiagnosisFollowUpRun> captured =
                ArgumentCaptor.forClass(DiagnosisFollowUpRun.class);
        verify(runs).insert(org.mockito.ArgumentMatchers.eq(1L), captured.capture());
        assertThat(captured.getValue().diagnosisVersion()).isEqualTo(stored.version());
        assertThat(captured.getValue().conclusionType()).isEqualTo(ConclusionType.HYPOTHESIS);
        assertThat(captured.getValue().toString())
                .doesNotContain("PC 端", "token", "must-not-persist");
        verify(persistence).get(1L, "diag-1");
        verifyNoMoreInteractions(persistence);
    }

    @Test
    void endCommandIsTheOnlyTurnThatEndsTheDiagnosisContext() {
        stubDiagnosisAndProjection();

        DiagnosisFollowUpResult result = service.respond(
                1L, "diag-1", "结束排障", "admin");

        assertThat(result.status()).isEqualTo(DiagnosisFollowUpStatus.ENDED);
        assertThat(result.intent()).isEqualTo(DiagnosisFollowUpIntent.END);
        assertThat(result.answer()).contains("已结束本次排障追问");
        assertThat(result.conclusionType()).isEqualTo(ConclusionType.HYPOTHESIS);
        assertThat(result.evidenceBasis()).isEqualTo(EvidenceBasis.OBSERVED);
        assertThat(result.fixtureMode()).isFalse();
        verifyNoMoreInteractions(runs);
    }

    @Test
    void keepsHypothesisAndReplaySemanticsAndDoesNotPresentCriteriaAsEvidence() {
        stubDiagnosisAndProjection();
        BusinessSummary summary = projections.project(1L, "diag-1").businessSummary();
        when(summary.evidenceBasis()).thenReturn(EvidenceBasis.RECORDED_REPLAY);
        DeveloperEvidenceView developer = projections.project(1L, "diag-1").developerEvidence();
        when(developer.steps()).thenReturn(List.of(
                new EvidenceStep(
                        EvidenceStepKind.CRITERION, null, "判据满足", "不应冒充证据",
                        "criterion-1", StepTone.ANOMALY),
                new EvidenceStep(
                        EvidenceStepKind.EVIDENCE, NOW, "失败日志聚合", "只读来源 · ANOMALY",
                        "evidence-1", StepTone.ANOMALY)));

        assertThat(service.respond(1L, "diag-1", "为什么？", "admin").answer())
                .contains("当前候选方向", "还不是已确认根因", "录制回放数据");
        DiagnosisFollowUpResult evidence =
                service.respond(1L, "diag-1", "有哪些证据？", "admin");
        assertThat(evidence.answer())
                .contains("待确认候选方向")
                .contains("失败日志聚合")
                .doesNotContain("不应冒充证据");
        assertThat(evidence.conclusionType()).isEqualTo(ConclusionType.HYPOTHESIS);
        assertThat(evidence.evidenceBasis()).isEqualTo(EvidenceBasis.RECORDED_REPLAY);
        assertThat(evidence.fixtureMode()).isFalse();
    }

    @Test
    void preservesReportedFactsEvenWhenTheDiagnosisIsMarkedAsFixture() {
        stubDiagnosisAndProjection();
        BusinessSummary summary = projections.project(1L, "diag-1").businessSummary();
        when(summary.evidenceBasis()).thenReturn(EvidenceBasis.REPORTED);
        when(summary.fixtureMode()).thenReturn(true);

        DiagnosisFollowUpResult result =
                service.respond(1L, "diag-1", "有哪些证据？", "admin");

        assertThat(result.answer())
                .contains("告警中已规范化记录的事实")
                .doesNotContain("演练数据");
        assertThat(result.evidenceBasis()).isEqualTo(EvidenceBasis.REPORTED);
        assertThat(result.fixtureMode()).isTrue();
    }

    @Test
    void preservesReplayFixtureSemanticsWhenEndingOrRecordingASupplement() {
        stubDiagnosisAndProjection();
        BusinessSummary summary = projections.project(1L, "diag-1").businessSummary();
        when(summary.evidenceBasis()).thenReturn(EvidenceBasis.RECORDED_REPLAY);
        when(summary.fixtureMode()).thenReturn(true);
        when(runs.insert(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(DiagnosisFollowUpRun.class)))
                .thenAnswer(call -> call.getArgument(1));

        DiagnosisFollowUpResult ended =
                service.respond(1L, "diag-1", "结束排障", "admin");
        DiagnosisFollowUpResult supplemented =
                service.respond(1L, "diag-1", "补充证据：补充一条脱敏事实", "admin");

        assertThat(ended.evidenceBasis()).isEqualTo(EvidenceBasis.RECORDED_REPLAY);
        assertThat(ended.fixtureMode()).isTrue();
        assertThat(supplemented.evidenceBasis()).isEqualTo(EvidenceBasis.RECORDED_REPLAY);
        assertThat(supplemented.fixtureMode()).isTrue();
    }

    private StoredDiagnosis stubStoredDiagnosis() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        lenient().when(diagnosis.diagnosisId()).thenReturn("diag-1");
        lenient().when(diagnosis.conclusionType()).thenReturn(ConclusionType.HYPOTHESIS);
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis, 7, true);
        when(persistence.get(1L, "diag-1")).thenReturn(stored);
        return stored;
    }

    private StoredDiagnosis stubDiagnosisAndProjection() {
        StoredDiagnosis stored = stubStoredDiagnosis();
        BusinessSummary summary = mock(BusinessSummary.class);
        lenient().when(summary.rootCause()).thenReturn("内容安全策略拦截请求");
        lenient().when(summary.conclusionType()).thenReturn(ConclusionType.HYPOTHESIS);
        lenient().when(summary.confidence()).thenReturn(Confidence.LOW);
        lenient().when(summary.evidenceBasis()).thenReturn(EvidenceBasis.OBSERVED);
        lenient().when(summary.fixtureMode()).thenReturn(false);
        lenient().when(summary.keyEvidence()).thenReturn("失败样本 2 条都出现该特征，正常样本 36 条均未出现");
        lenient().when(summary.narrative()).thenReturn("该特征与本次失败明显相关");
        lenient().when(summary.nextStep()).thenReturn(new NextStep(
                "人工核对",
                "核对内容安全策略并确认是否需要调整",
                "平台只读，不执行策略变更"));
        DeveloperEvidenceView developer = mock(DeveloperEvidenceView.class);
        lenient().when(developer.capabilityLimits()).thenReturn(List.of("未取得上游网关响应体"));
        lenient().when(developer.steps()).thenReturn(List.of());
        DiagnosisExperienceProjection projection = mock(DiagnosisExperienceProjection.class);
        when(projection.businessSummary()).thenReturn(summary);
        lenient().when(projection.developerEvidence()).thenReturn(developer);
        when(projections.project(1L, "diag-1")).thenReturn(projection);
        return stored;
    }
}
