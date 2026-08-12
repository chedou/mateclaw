import type { AxiosInstance } from 'axios'
import type {
  AcceptGuanceEvidenceRequest,
  ActionOutcomeStatus,
  ApprovedPlaybookVersion,
  BaselineEvaluationLedger,
  CaptureEvaluationSampleRequest,
  CaptureRecordedReplayEvaluationSampleRequest,
  ClosureOutcome,
  ConversationTurnRequest,
  ConversationTurnResult,
  CreateDeploymentTopologyScenarioRequest,
  DeclareEvidenceRouteRequest,
  DeclareEvidenceContractRequest,
  DeclareObservabilityAssetRequest,
  DeploymentTopologyAssetSummary,
  DeploymentTopologyImportResult,
  DeploymentTopologySopResult,
  DiagnosisDerivation,
  DiagnosisExperienceProjection,
  DiagnosisSummary,
  EvidenceChainPreviewRequest,
  EvidenceEvaluationSample,
  EvidenceEvaluationSampleLedger,
  EvidenceQueryCatalog,
  EvidenceContractTrial,
  EvidenceContractTrialRequest,
  EvidenceRouteDeclaration,
  FinalizeEvaluationSampleReferenceRequest,
  GuanceEvidenceAcceptanceView,
  GuanceEvidenceReadiness,
  OpenDiscoveryReadiness,
  OpenDiscoveryAgentBinding,
  GuanceEvidenceSpinePreview,
  GuanceEvidenceValidationReport,
  GuanceRecordingTargetCatalogView,
  HistoricalCaseKnowledgeImportRequest,
  HistoricalCaseKnowledgeImportResult,
  IncidentReportRequest,
  InvestigationMode,
  InvestigationProvenance,
  KnowledgeEvidenceCoverage,
  KnowledgeOrigin,
  KnowledgeReviewApproval,
  KnowledgeReviewDecisionRequest,
  KnowledgeReviewDeprecation,
  KnowledgeReviewInbox,
  KnowledgeReviewState,
  ManualPlaybookReplayAttestation,
  ObservabilityAsset,
  EvidenceContractCatalog,
  EvidenceContractView,
  ObservabilityAssetCatalog,
  RecordedReplayEvaluationCapability,
  RunBaselineEvaluationRequest,
  ScenarioDiagnosisRequest,
  SopEntry,
  SopStatus,
  SopSummary,
  SopSynthesisPreview,
  SopSynthesisPreviewRequest,
  StoredBaselineEvaluationRun,
  StoredDiagnosis,
  StoredEvidenceEvaluationSample,
  TopologyProbeEvidenceRun,
} from './troubleshooting-contracts'

/** Builds the troubleshooting client without owning authentication or workspace interceptors. */
export const createTroubleshootingApi = (http: AxiosInstance) => ({
  /** Report an incident. A retry inside the dedup bucket returns `created: false`. */
  report: (data: IncidentReportRequest) =>
    http.post<StoredDiagnosis>('/troubleshooting/incidents', data),

  /** Multi-turn Web conversation intake; READY returns the same Diagnosis as WeCom. */
  conversationTurn: (data: ConversationTurnRequest) =>
    http.post<ConversationTurnResult>('/troubleshooting/conversation/turns', data),

  /** Opens one exact approved scenario without claiming a cause. */
  createScenarioDiagnosis: (scenarioKey: string, data: ScenarioDiagnosisRequest) =>
    http.post<StoredDiagnosis>(
      `/troubleshooting/scenarios/${encodeURIComponent(scenarioKey)}/diagnoses`, data,
    ),

  /** Executes only the evidence contract frozen on the waiting Diagnosis. */
  runScenarioEvidence: (diagnosisId: string) =>
    http.post<StoredDiagnosis>(
      `/troubleshooting/diagnoses/${encodeURIComponent(diagnosisId)}/evidence-runs`,
    ),

  /** Backfills safe Diagnosis snapshots into one existing Wiki knowledge base. */
  importHistoricalCases: (data: HistoricalCaseKnowledgeImportRequest) =>
    http.post<HistoricalCaseKnowledgeImportResult>(
      '/troubleshooting/knowledge/case-imports', data, { timeout: 120000 },
    ),

  /** Creates the Diagnosis owner before any deployment-topology evidence Tool may run. */
  createDeploymentTopologyScenario: (data: CreateDeploymentTopologyScenarioRequest) =>
    http.post<StoredDiagnosis>(
      '/troubleshooting/scenarios/deployment-topology/diagnoses', data,
    ),

  list: (params?: {
    status?: string
    system?: string
    investigationMode?: InvestigationMode
    limit?: number
  }) =>
    http.get<DiagnosisSummary[]>('/troubleshooting/diagnoses', { params }),

  get: (diagnosisId: string) =>
    http.get<StoredDiagnosis>(`/troubleshooting/diagnoses/${diagnosisId}`),

  /** How the conclusion was reached: criteria with substituted arithmetic, and losing rules. */
  derivation: (diagnosisId: string) =>
    http.get<DiagnosisDerivation>(`/troubleshooting/diagnoses/${diagnosisId}/derivation`),
  provenance: (diagnosisId: string) =>
    http.get<InvestigationProvenance>(`/troubleshooting/diagnoses/${diagnosisId}/provenance`),

  /** One diagnosis projected for the service-manager summary and developer evidence desk. */
  projection: (diagnosisId: string) =>
    http.get<DiagnosisExperienceProjection>(
      `/troubleshooting/diagnoses/${diagnosisId}/projection`,
    ),

  /** Inspects bindings for this exact workspace asset without probing Guance. */
  evidenceReadiness: (params: { system: string; service: string }) =>
    http.get<GuanceEvidenceReadiness>('/troubleshooting/evidence/readiness', { params }),

  /** Secret-free OPEN_DISCOVERY / miss-path readiness; does not call a model. */
  openDiscoveryReadiness: (params?: { system?: string }) =>
    http.get<OpenDiscoveryReadiness>('/troubleshooting/open-discovery/readiness', { params }),

  /** Current workspace digital-employee binding for OPEN_DISCOVERY. */
  openDiscoveryAgentBinding: () =>
    http.get<OpenDiscoveryAgentBinding>('/troubleshooting/open-discovery/agent-binding'),

  /** Bind a digital employee as the OPEN_DISCOVERY executor. */
  bindOpenDiscoveryAgent: (data: { agentId: number | string; prepareEvidenceTool?: boolean }) =>
    http.put<OpenDiscoveryAgentBinding>('/troubleshooting/open-discovery/agent-binding', {
      agentId: Number(data.agentId),
      prepareEvidenceTool: data.prepareEvidenceTool ?? true,
    }),

  /** Clear workspace binding and fall back to process config agent-id. */
  clearOpenDiscoveryAgentBinding: () =>
    http.delete<OpenDiscoveryAgentBinding>('/troubleshooting/open-discovery/agent-binding'),

  /** Scenario-oriented contract directory; reads configuration without querying a source. */
  evidenceCatalog: () => http.get<EvidenceQueryCatalog>(
    '/troubleshooting/evidence/catalog',
  ),

  /** Admin-only bounded read-only query; the response and audit contain no raw evidence. */
  runEvidenceContractTrial: (data: EvidenceContractTrialRequest) =>
    http.post<EvidenceContractTrial>('/troubleshooting/evidence/contract-trials', data),

  /** Reads immutable secret-free trial audits for one catalog selection. */
  evidenceContractTrials: (params?: {
    system?: string
    service?: string
    contractRef?: string
    limit?: number
  }) => http.get<EvidenceContractTrial[]>(
    '/troubleshooting/evidence/contract-trials', { params },
  ),

  /** Replaces one system + signal route; an empty platform list explicitly disables it. */
  declareEvidenceRoute: (data: DeclareEvidenceRouteRequest) =>
    http.put<EvidenceRouteDeclaration>('/troubleshooting/evidence/routes', data),

  /** Removes the workspace declaration and restores the deployment fallback. */
  withdrawEvidenceRoute: (system: string, signalKind: string) =>
    http.delete<void>('/troubleshooting/evidence/routes', {
      params: { system, signalKind },
    }),

  /** Workspace-owned system/resource scopes; contains no endpoint, key or raw DQL. */
  observabilityAssets: () => http.get<ObservabilityAssetCatalog>(
    '/troubleshooting/evidence/assets',
  ),

  /** Method library: deployment + workspace contracts (list hides query templates). */
  evidenceContracts: () => http.get<EvidenceContractCatalog>(
    '/troubleshooting/evidence/contracts',
  ),

  /** Admin detail includes query template for editing. */
  evidenceContractDetail: (contractRef: string) =>
    http.get<EvidenceContractView>(
      `/troubleshooting/evidence/contracts/${encodeURIComponent(contractRef)}`,
    ),

  /** Adds the next immutable workspace contract revision. */
  declareEvidenceContract: (data: DeclareEvidenceContractRequest) =>
    http.put<EvidenceContractView>('/troubleshooting/evidence/contracts', data),

  /** Adds the next immutable asset revision; existing versions remain auditable. */
  declareObservabilityAsset: (data: DeclareObservabilityAssetRequest) =>
    http.put<ObservabilityAsset>('/troubleshooting/evidence/assets', data),

  /** Persistent owner acceptance for the exact current Guance binding fingerprint. */
  guanceEvidenceAcceptance: (params: { system: string; service: string }) =>
    http.get<GuanceEvidenceAcceptanceView>(
      '/troubleshooting/evidence/guance/acceptance', { params },
    ),

  /** Server-frozen, unrecorded D1 targets that match the current three bindings. */
  guanceRecordingTargets: (params: { system: string; service: string }) =>
    http.get<GuanceRecordingTargetCatalogView>(
      '/troubleshooting/evidence/guance/recording-targets', { params },
    ),

  /** Re-runs the canonical chain before recording the owner checklist. */
  acceptGuanceEvidence: (data: AcceptGuanceEvidenceRequest) =>
    http.post<GuanceEvidenceAcceptanceView>(
      '/troubleshooting/evidence/guance/acceptance', data,
    ),

  /** Admin-only Guance chain; one run does not by itself complete T7 or T8. */
  validateGuanceEvidence: (data: EvidenceChainPreviewRequest) => http.post<GuanceEvidenceValidationReport>(
    '/troubleshooting/evidence/guance/validate', data,
  ),

  /** Runs the shared three-stage spine against Guance only; never falls back to Replay. */
  previewGuanceEvidenceSpine: (data: EvidenceChainPreviewRequest) => http.post<GuanceEvidenceSpinePreview>(
    '/troubleshooting/evidence/guance/spine/preview', data,
  ),

  /** Parses a deployment snapshot and runs only its server-authorized Guance probes. */
  analyzeDeploymentTopology: (snapshot: Record<string, unknown>) =>
    http.post<DeploymentTopologySopResult>(
      '/troubleshooting/sops/deployment-topology/analyze', { snapshot },
    ),

  /** Lists immutable, workspace-shared deployment topology snapshots. */
  listDeploymentTopologies: () => http.get<DeploymentTopologyAssetSummary[]>(
    '/troubleshooting/sops/deployment-topology/topologies',
  ),

  /** Validates and imports one topology for reuse by workspace administrators. */
  importDeploymentTopology: (data: { name: string; snapshot: Record<string, unknown> }) =>
    http.post<DeploymentTopologyImportResult>(
      '/troubleshooting/sops/deployment-topology/topologies', data,
    ),

  /** Downloads a valid, secret-free topology example. */
  deploymentTopologyExample: () => http.get<Record<string, unknown>>(
    '/troubleshooting/sops/deployment-topology/example',
  ),

  /** Analyzes the exact stored topology server-side without resubmitting its snapshot. */
  analyzeImportedDeploymentTopology: (topologyId: string) =>
    http.post<DeploymentTopologySopResult>(
      `/troubleshooting/sops/deployment-topology/topologies/${encodeURIComponent(topologyId)}/analyze`,
    ),

  /** Runs the topology scenario and persists only its safe projection under one Diagnosis. */
  runDiagnosisTopologyProbe: (diagnosisId: string, topologyId: string) =>
    http.post<TopologyProbeEvidenceRun>(
      `/troubleshooting/diagnoses/${encodeURIComponent(diagnosisId)}/topology-probe-runs`,
      { topologyId },
    ),

  /** Lists immutable topology evidence runs already attached to one Diagnosis. */
  diagnosisTopologyProbeRuns: (diagnosisId: string, limit = 50) =>
    http.get<TopologyProbeEvidenceRun[]>(
      `/troubleshooting/diagnoses/${encodeURIComponent(diagnosisId)}/topology-probe-runs`,
      { params: { limit } },
    ),

  /** Re-runs Guance server-side and stores only a bounded, secret-free T8 sample. */
  captureGuanceEvaluationSample: (data: CaptureEvaluationSampleRequest) =>
    http.post<StoredEvidenceEvaluationSample>(
      '/troubleshooting/evaluation-samples/guance', data,
    ),

  /** Runs only the registered fixture Replay adapter and stores a separate T8 sample. */
  captureRecordedReplayEvaluationSample: (data: CaptureRecordedReplayEvaluationSampleRequest) =>
    http.post<StoredEvidenceEvaluationSample>(
      '/troubleshooting/evaluation-samples/recorded-replay', data,
    ),

  /** Exact server-owned adapter, route, fixture catalog and scope readiness. */
  recordedReplayEvaluationCapability: (params: { diagnosisId: string }) =>
    http.get<RecordedReplayEvaluationCapability>(
    '/troubleshooting/evaluation-samples/recorded-replay/capability', { params },
  ),

  /** Lists sample accumulation only; the response deliberately has no gate-pass verdict. */
  evaluationSamples: (params?: { diagnosisId?: string; limit?: number }) =>
    http.get<EvidenceEvaluationSampleLedger>(
      '/troubleshooting/evaluation-samples', { params },
    ),

  /** Stores human-authored intent keys; closure outcome is derived by the server. */
  finalizeEvaluationSampleReference: (
    sampleId: string,
    data: FinalizeEvaluationSampleReferenceRequest,
  ) => http.put<EvidenceEvaluationSample>(
    `/troubleshooting/evaluation-samples/${encodeURIComponent(sampleId)}/reference`, data,
  ),

  /** Replays the frozen source input and invokes one pinned model without writing a candidate. */
  runEvaluationBaseline: (
    sampleId: string,
    data: RunBaselineEvaluationRequest,
  ) => http.post<StoredBaselineEvaluationRun>(
    `/troubleshooting/evaluation-samples/${encodeURIComponent(sampleId)}/baseline-runs`, data,
  ),

  /** Lists source-separated descriptive baseline facts; never returns a Gate verdict. */
  evaluationBaselineRuns: (params?: { diagnosisId?: string; limit?: number }) =>
    http.get<BaselineEvaluationLedger>(
      '/troubleshooting/evaluation-samples/baseline-runs', { params },
    ),

  /** Runs the meeting-case evidence lane without invoking a model or writing a candidate. */
  previewSopSynthesis: (data: SopSynthesisPreviewRequest) =>
    http.post<SopSynthesisPreview>('/troubleshooting/sops/synthesis/preview', data),

  listSops: (params?: { status?: SopStatus; system?: string; limit?: number }) =>
    http.get<SopSummary[]>('/troubleshooting/sops', { params }),

  knowledgeEvidenceCoverage: () =>
    http.get<KnowledgeEvidenceCoverage>('/troubleshooting/sops/evidence-coverage'),

  /** Three source lanes plus their independent review states; no promotion side effect. */
  knowledgeReviewInbox: (params?: { limit?: number }) =>
    http.get<KnowledgeReviewInbox>('/troubleshooting/sops/review-inbox', { params }),

  /** Runs the server-owned fixed suite; callers cannot submit cases or expected answers. */
  replayManualKnowledgeCandidate: (sourceRecordId: string) =>
    http.post<ManualPlaybookReplayAttestation>(
      `/troubleshooting/sops/review-inbox/manual/`
        + `${encodeURIComponent(sourceRecordId)}/replay`,
    ),

  /** Downloads an import-safe candidate example; replay cases stay server-side. */
  manualKnowledgeCandidateExample: (selectorKey: string) =>
    http.get<SopEntry>('/troubleshooting/sops/review-inbox/manual/example', {
      params: { selectorKey },
    }),

  /** Starts optimistic review from the virtual CANDIDATE/v0 state. */
  startKnowledgeReview: (
    origin: KnowledgeOrigin,
    sourceRecordId: string,
    data: KnowledgeReviewDecisionRequest,
  ) => http.post<KnowledgeReviewState>(
    `/troubleshooting/sops/review-inbox/${encodeURIComponent(origin)}`
      + `/${encodeURIComponent(sourceRecordId)}/start`,
    data,
  ),

  /** Records rejection for the exact IN_REVIEW version. */
  rejectKnowledgeReview: (
    origin: KnowledgeOrigin,
    sourceRecordId: string,
    data: KnowledgeReviewDecisionRequest,
  ) => http.post<KnowledgeReviewState>(
    `/troubleshooting/sops/review-inbox/${encodeURIComponent(origin)}`
      + `/${encodeURIComponent(sourceRecordId)}/reject`,
    data,
  ),

  /** Server-gated approval that always creates a new immutable Playbook version. */
  approveKnowledgeReview: (
    origin: KnowledgeOrigin,
    sourceRecordId: string,
    data: KnowledgeReviewDecisionRequest,
  ) => http.post<KnowledgeReviewApproval>(
    `/troubleshooting/sops/review-inbox/${encodeURIComponent(origin)}`
      + `/${encodeURIComponent(sourceRecordId)}/approve`,
    data,
  ),

  /** Retires the exact active version created by an approved review. */
  deprecateKnowledgeReview: (
    origin: KnowledgeOrigin,
    sourceRecordId: string,
    data: KnowledgeReviewDecisionRequest,
  ) => http.post<KnowledgeReviewDeprecation>(
    `/troubleshooting/sops/review-inbox/${encodeURIComponent(origin)}`
      + `/${encodeURIComponent(sourceRecordId)}/deprecate`,
    data,
  ),

  /** Audited retirement for a V186 legacy authority without a review row. */
  deprecateLegacyPlaybook: (
    playbookId: string,
    data: { expectedPlaybookVersion: number; reason: string },
  ) => http.post<ApprovedPlaybookVersion>(
    `/troubleshooting/sops/versions/${encodeURIComponent(playbookId)}/deprecate`,
    data,
  ),

  getSop: (system: string, errorCode: string) =>
    http.get<SopEntry>(
      `/troubleshooting/sops/${encodeURIComponent(system)}/${encodeURIComponent(errorCode)}`,
    ),

  /** Exact manual source lookup; does not resolve to the active selector version. */
  getSopById: (sopId: string) =>
    http.get<SopEntry>(`/troubleshooting/sops/by-id/${encodeURIComponent(sopId)}`),

  /** Create-only: the server accepts only candidate + verified=false. */
  registerSop: (data: SopEntry) =>
    http.post<SopEntry>('/troubleshooting/sops', data),

  /** Compatibility transition: only retires a non-versioned approved row. */
  updateSopStatus: (system: string, errorCode: string, status: 'deprecated') =>
    http.post<SopEntry>(
      `/troubleshooting/sops/${encodeURIComponent(system)}/${encodeURIComponent(errorCode)}/status`,
      { status },
    ),

  confirm: (diagnosisId: string) =>
    http.post<StoredDiagnosis>(`/troubleshooting/diagnoses/${diagnosisId}/confirm`),

  transfer: (diagnosisId: string, data: { targetTeam: string; note: string }) =>
    http.post<StoredDiagnosis>(`/troubleshooting/diagnoses/${diagnosisId}/transfer`, data),

  /** Authorizes a manual write without executing it: PENDING -> APPROVED_NOT_EXECUTED. */
  approveAction: (diagnosisId: string, actionId: string, data: { reason: string }) =>
    http.post<StoredDiagnosis>(
      `/troubleshooting/diagnoses/${diagnosisId}/actions/${actionId}/approve`, data),

  /** Records what happened when a human ran the approved write outside MateClaw. */
  recordOutcome: (
    diagnosisId: string,
    actionId: string,
    data: { outcome: ActionOutcomeStatus; notes: string; recoveryVerified: boolean },
  ) =>
    http.post<StoredDiagnosis>(
      `/troubleshooting/diagnoses/${diagnosisId}/actions/${actionId}/record-outcome`, data),

  close: (
    diagnosisId: string,
    data: {
      outcome: ClosureOutcome
      summary: string
      recoveryVerified: boolean
      sopFeedback?: string | null
      createKnowledgeCandidate: boolean
    },
  ) => http.post<StoredDiagnosis>(`/troubleshooting/diagnoses/${diagnosisId}/close`, data),
})

export type TroubleshootingApi = ReturnType<typeof createTroubleshootingApi>
