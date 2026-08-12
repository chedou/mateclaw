// ==================== Troubleshooting ====================

/** Mirrors vip.mate.troubleshooting.model.* — keep in step with the Java contract. */
export type DiagnosisStatus =
  | 'READY_FOR_HUMAN'
  | 'NEEDS_INVESTIGATION'
  | 'CONFIRMED'
  | 'TRANSFERRED'
  | 'CLOSED'
export type RouteMode = 'DETERMINISTIC' | 'LLM_FALLBACK'
/** Three levels, not a score: a deterministic rule engine cannot calibrate a probability. */
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW'
export type EvidenceStatus = 'NORMAL' | 'ANOMALY' | 'MISSING'
export type ActionType = 'AUTO_READONLY' | 'HUMAN_CONTACT' | 'MANUAL_UNKNOWN' | 'MANUAL_WRITE'
export type ApprovalStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED_NOT_EXECUTED'
export type ExecutionStatus = 'COMPLETED' | 'PENDING' | 'BLOCKED' | 'NOT_APPLICABLE'
export type ActionOutcomeStatus = 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
export type ClosureOutcome = 'RECOVERED' | 'FALSE_POSITIVE' | 'TRANSFERRED_OUT' | 'UNRESOLVED'
export type IncidentCompleteness = 'STRUCTURED' | 'LOG' | 'SYMPTOM'
export type IncidentSeverity = 'P0' | 'P1' | 'P2' | 'P3'
export type SopStatus = 'candidate' | 'approved' | 'deprecated'
export type KnowledgeEvidenceGrade =
  | 'RECORDED_AGGREGATE'
  | 'AUTHORED_FIXTURE'
  | 'UNVERIFIED'

/**
 * Browser incident-intake boundary.
 *
 * Deliberately excludes caller-owned evidence, raw logs, impact counts and an
 * incident id. Those facts must come from the server-owned evidence spine or
 * be derived by the domain rather than being asserted by a console form.
 */
export interface IncidentReportRequest {
  system: string
  service: string
  title: string
  severity: IncidentSeverity
  errorCode?: string
  traceId?: string
  occurredAt?: string
  intakeSource: 'web:formal-workbench'
  completeness: IncidentCompleteness
  rehearsal: boolean
}

/** One turn of the Web conversation intake that reuses IntakeSession. */
export interface ConversationTurnRequest {
  conversationId?: string | null
  text: string
  rehearsal: boolean
}

export interface ConversationTurnResult {
  conversationId: string
  intakeSessionId: string
  status: 'RECEIVED' | 'AWAITING_INPUT' | 'READY' | string
  missingFields: string[]
  prompt: string
  duplicate: boolean
  outOfOrder: boolean
  diagnosisId: string | null
  created: boolean | null
  rehearsal: boolean
}

/** Explicit topology-scenario intake; routing, Playbook and Tool keys are server-owned. */
export interface CreateDeploymentTopologyScenarioRequest {
  system: string
  service: string
  title: string
  severity: IncidentSeverity
  traceId?: string
  rehearsal: boolean
}

/** Explicit no-error-code scenario intake; evidence plan and Playbook stay server-owned. */
export interface ScenarioDiagnosisRequest {
  system: string
  service: string
  title: string
  severity: IncidentSeverity
  traceId?: string
  customerRef?: string
  occurredAt?: string | null
  rehearsal: boolean
}

export type HistoricalCaseKnowledgeImportState =
  | 'IMPORTED_VECTOR_READY'
  | 'IMPORTED_VECTOR_PENDING'
  | 'REUSED_VECTOR_READY'
  | 'REUSED_VECTOR_PENDING'
  | 'FAILED'

/** Safe receipt only. Case payloads and original evidence are never echoed here. */
export interface HistoricalCaseKnowledgeImportItem {
  diagnosisId: string
  caseId: string
  slug: string | null
  state: HistoricalCaseKnowledgeImportState
  authoritativeResolution: boolean
  chunkCount: number
  embeddedChunkCount: number
  error: string | null
}

export interface HistoricalCaseKnowledgeImportResult {
  knowledgeBaseId: string | number
  discovered: number
  imported: number
  reused: number
  vectorReady: number
  vectorPending: number
  failed: number
  items: HistoricalCaseKnowledgeImportItem[]
}

export interface HistoricalCaseKnowledgeImportRequest {
  knowledgeBaseId: string | number
  limit: number
}

/** Authoritative deterministic knowledge contract managed outside the diagnosis lifecycle. */
export interface SopEntry {
  sopId: string
  contractVersion: string
  system: string
  errorCode: string
  service: string
  title: string
  cause: string
  category: string
  ownerTeam: string | null
  status: SopStatus
  verified: boolean
  evidenceRequests: Record<string, unknown>[]
  anomalyCriteria: Record<string, unknown>[]
  diagnosisRules: Record<string, unknown>[]
  actions: Record<string, unknown>[]
}

/** Indexed registry row; full nested contracts are fetched only for the selected route. */
export interface SopSummary {
  sopId: string
  routeKey: string
  system: string
  errorCode: string
  service: string
  status: SopStatus
  verified: boolean
  operational: boolean
  createTime: string
  updateTime: string
  playbookVersion?: number | null
  sourceOrigin?: KnowledgeOrigin | 'LEGACY' | null
  sourceRecordId?: string | null
  reviewId?: string | null
  reviewVersion?: number | null
  knowledgeEvidenceGrade: KnowledgeEvidenceGrade
}

/** Counts with the reviewed CSDP inventory denominator; deliberately no rate. */
export interface KnowledgeEvidenceCoverage {
  inventoryErrorCodeSelectors: number
  registryErrorCodeSelectors: number
  recordedAggregateSelectors: number
  authoredFixtureSelectors: number
  unverifiedSelectors: number
  outsideInventorySelectors: number
}

export type KnowledgeOrigin = 'EVIDENCE_DERIVED' | 'OUTCOME_BACKED' | 'MANUAL'
export type KnowledgeReviewStatus =
  | 'DRAFT' | 'CANDIDATE' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'DEPRECATED'
export type KnowledgeValidationStatus = 'VALID' | 'INVALID' | 'PENDING' | 'NOT_EVALUATED'
export type KnowledgeApprovalEligibility = 'NOT_ELIGIBLE' | 'ELIGIBLE_FOR_APPROVAL'

export interface PlaybookDraftSelector {
  system: string
  scenarioKey: string | null
  errorCode: string | null
}

export interface PlaybookDraftModelProvenance {
  provider: string
  modelName: string
  modelConfigVersion: string
  draftContractVersion: string
  generatedAt: string
  invocationCount: number
}

export interface PlaybookDraft {
  draftId: string
  generationKey: string
  sourceIncident: string | null
  proposedType: 'ERROR_CODE' | 'SCENARIO'
  proposedSelector: PlaybookDraftSelector
  title: string
  evidencePlan: Array<{
    intentKey: string
    signalKind: string
    purpose: string
    required: boolean
  }>
  criteria: Array<{
    criterionKey: string
    description: string
    evidenceKinds: string[]
    evidenceCitations: string[]
  }>
  diagnosisHypotheses: Array<{
    hypothesisKey: string
    summary: string
    evidenceCitations: string[]
  }>
  humanActions: Array<{
    intentKey: string
    instruction: string
    executionMode: string
    evidenceCitations: string[]
  }>
  evidenceCitations: string[]
  modelProvenance: PlaybookDraftModelProvenance
  contrastAvailable: boolean
  validationErrors: Array<{ code: string; fieldPath: string; message: string }>
}

export interface ReferenceSolutionComparison {
  referenceId: string
  passed: boolean
  requiredIntentCoverage: number
  missingStepIntents: string[]
  forbiddenStepIntentsPresent: string[]
  orderingViolations: string[]
  missingEvidenceKinds: string[]
}

export interface PlaybookKnowledgeRecord {
  recordId: string
  draft: PlaybookDraft
  origin: 'EVIDENCE_DERIVED'
  reviewStatus: KnowledgeReviewStatus
  validationStatus: KnowledgeValidationStatus
  reviewer: string
  reviewReason: string
  evidenceBundleId: string
  service: string
  referenceComparison: ReferenceSolutionComparison
  approvalEligibility: KnowledgeApprovalEligibility
  eligibilityReasons: string[]
  fixtureMode: boolean
  timings: NorthStarTimings
  createdAt: string
}

export interface KnowledgeActionOutcome {
  outcomeId: string
  actionId: string
  outcome: ActionOutcomeStatus
  notes: string
  recoveryVerified: boolean
  actor: string
  recordedAt: string
}

export interface KnowledgeCandidate {
  candidateId: string
  contractVersion: string
  sourceDiagnosisId: string
  sourceCaseId: string
  sourceRunId: string
  system: string
  errorCode: string | null
  sopKey: string | null
  rootCause: string
  evidenceIds: string[]
  recommendedActions: RecommendedAction[]
  actionOutcomes: KnowledgeActionOutcome[]
  resolutionSummary: string
  feedback: string | null
  createdBy: string
  createdAt: string
  /** Missing only on legacy knowledge-candidate.v1 outbox rows. */
  outcomeProof?: {
    outcome: ClosureOutcome
    recoveryVerified: boolean
    registeredBy: string
    registeredAt: string
  } | null
  /** Frozen from the Playbook used by the source Diagnosis; never inferred from transfer. */
  ownerTeam?: string | null
}

export interface KnowledgeReviewSnapshot {
  validationStatus: KnowledgeValidationStatus
  qualificationPhase: 'CALIBRATION' | 'RUNTIME' | 'NOT_APPLICABLE' | 'UNKNOWN'
  validationErrors: PlaybookDraft['validationErrors']
  referenceComparison: ReferenceSolutionComparison | null
  modelConfigVersion: string | null
  approvalEligibility: KnowledgeApprovalEligibility
  eligibilityReasons: string[]
  fixtureMode: boolean | null
  /** Present only when a MANUAL source has a persisted exact replay result. */
  manualReplay?: ManualPlaybookReplayAttestation | null
}

/** Bounded candidate-and-suite-scoped replay proof; contains no raw fixture evidence. */
export interface ManualPlaybookReplayAttestation {
  attestationId: string
  sourceRecordId: string
  selectorKey: string
  candidateFingerprint: string
  suiteId: string
  suiteVersion: number
  suiteFingerprint: string
  status: 'PASSED' | 'FAILED'
  positiveTotal: number
  positivePassed: number
  negativeOrAbstainTotal: number
  negativeOrAbstainPassed: number
  failureCodes: string[]
  fixtureMode: true
  executedBy: string
  executedAt: string
}

export interface KnowledgeReviewState {
  reviewId: string
  origin: KnowledgeOrigin
  sourceRecordId: string
  selectorKey: string | null
  status: KnowledgeReviewStatus
  reviewer: string
  reason: string
  snapshot: KnowledgeReviewSnapshot
  version: number
  createdAt: string
  updatedAt: string
}

/** Current server-computed qualification for one exact persisted source row. */
export interface KnowledgeReviewSourceState {
  origin: KnowledgeOrigin
  sourceRecordId: string
  selectorKey: string | null
  snapshot: KnowledgeReviewSnapshot
}

export interface KnowledgeReviewDecisionRequest {
  expectedVersion: number
  reason: string
}

export interface ApprovedPlaybookVersion {
  playbookId: string
  playbookVersion: number
  selectorKey: string
  status: 'APPROVED' | 'DEPRECATED'
  sourceOrigin: KnowledgeOrigin | 'LEGACY'
  sourceRecordId: string
  knowledgeEvidenceGrade: KnowledgeEvidenceGrade
  reviewId: string | null
  reviewVersion: number | null
  approvedBy: string
  approvalReason: string
  approvalSnapshot: KnowledgeReviewSnapshot | null
  deprecatedBy?: string | null
  deprecationReason?: string | null
  deprecatedAt?: string | null
  playbook: SopEntry
  createdAt: string
  updatedAt: string
}

export interface KnowledgeReviewApproval {
  review: KnowledgeReviewState
  approvedVersion: ApprovedPlaybookVersion
}

export interface KnowledgeReviewDeprecation {
  review: KnowledgeReviewState
  deprecatedVersion: ApprovedPlaybookVersion
}

/** Knowledge governance projection; start/reject are separate optimistic commands. */
export interface KnowledgeReviewInbox {
  evidenceDerived: PlaybookKnowledgeRecord[]
  outcomeBacked: KnowledgeCandidate[]
  manual: SopSummary[]
  sourceStates: KnowledgeReviewSourceState[]
  reviewStates: KnowledgeReviewState[]
  capabilityLimits: string[]
}

/** Shared bounded request shape for deterministic evidence-chain probes. */
export interface EvidenceChainPreviewRequest {
  system: string
  service: string
  searchTerm: string
  window: string
  occurredAt: string | null
}

/** Fixture-confined input for the no-error-code Evidence -> call-chain preview. */
export type SopSynthesisPreviewRequest = EvidenceChainPreviewRequest

export interface SynthesisEvidenceReference {
  queryId: string
  status: EvidenceStatus
  source: string
  collectedAt: string
}

export interface LogTraceTimelineEvent {
  sequenceIndex: number
  offsetMs: number
  service: string
  level: string
  message: string
  durationMs: number | null
  anomalous: boolean
}

export interface LogTraceDurationSummary {
  sampleCount: number
  minMs: number
  maxMs: number
  averageMs: number
}

export interface LogTraceContrastSummary {
  available: boolean
  discriminatingFeature: string
  failureSampleCount: number | string
  failureMatchCount: number | string
  successSampleCount: number | string
  successMatchCount: number | string
  failureRate: number
  successRate: number
  rateDelta: number
}

/** Bounded deterministic projection; it is safe to expose, unlike the raw trace bundle. */
export interface LogTraceSkeleton {
  psId: string
  startedAtEpochMs: number
  endedAtEpochMs: number
  elapsedMs: number
  serviceSequence: string[]
  timeline: LogTraceTimelineEvent[]
  anomalySequenceIndexes: number[]
  durationByService: Record<string, LogTraceDurationSummary>
  sourceEntryCount: number
  omittedEntryCount: number
  contrast: LogTraceContrastSummary
}

/** Read-only result after three routed evidence steps and deterministic compression. */
export interface SopSynthesisPreview {
  stage: 'READY_FOR_MODEL'
  system: string
  service: string
  searchTerm: string
  matchCount: number
  psId: string
  searchEvidence: SynthesisEvidenceReference
  traceEvidence: SynthesisEvidenceReference
  contrastEvidence: SynthesisEvidenceReference | null
  skeleton: LogTraceSkeleton
  fixtureMode: boolean
  traceEntries: number
  sourceRequestCount: number
  totalDurationMs: number
  timings: EvidenceSpineTimings
  completedAt: string
  contrastAvailable: boolean
  warnings: string[]
}

export interface IncidentContext {
  incidentId: string
  system: string
  service: string
  errorCode: string | null
  title: string
  severity: string
  /** v1.6 writes the object; string remains readable for v1.3-v1.5 rows. */
  impact: IncidentImpact | string
  traceId: string | null
  occurredAt: string
  slaRemaining: string | null
  intakeSource: string
  completeness: IncidentCompleteness
  rawInput: string | null
}

export interface IncidentImpact {
  functionScope: string
  affectedCustomers: number | null
  affectedUsers: number | null
  blastRadius: BlastRadius
  evidenceRefs: string[]
  observedAt: string | null
  note: string
}

export interface EvidenceResult {
  queryId: string
  namespace: string
  query: string
  status: EvidenceStatus
  summary: string
  observed: Record<string, unknown>
  source: string
  collectedAt: string
}

export interface RecommendedAction {
  actionId: string
  actionType: ActionType
  title: string
  description: string
  requiresApproval: boolean
  approvalStatus: ApprovalStatus
  executionStatus: ExecutionStatus
}

export interface TimelineEvent {
  timestamp: string
  event: string
  actor: string
  /** `current` marks the step awaiting someone; everything before it is `done`. */
  status: string
}

export interface ClosureRecord {
  outcome: ClosureOutcome
  summary: string
  recoveryVerified: boolean
  sopFeedback: string | null
  knowledgeCandidateId: string | null
  actor: string
  closedAt: string
}

export interface PlaybookVersionRef {
  playbookId: string
  playbookVersion: number
}

export interface Diagnosis {
  diagnosisId: string
  contractVersion: string
  caseId: string
  runId: string
  incident: IncidentContext
  routeMode: RouteMode
  /** v4 route semantics; do not infer either value from legacy routeMode. */
  investigationMode: InvestigationMode
  routeAuthority: RouteAuthority
  conclusionType: ConclusionType
  status: DiagnosisStatus
  summary: string
  rootCause: string
  confidence: Confidence
  abstained: boolean
  sopKey: string | null
  sopTitle: string | null
  evidence: EvidenceResult[]
  /** Query ids that actually support a miss-path Agent suggestion. */
  evidenceCitations: string[]
  triggeredSignals: string[]
  recommendedActions: RecommendedAction[]
  pendingWrites: RecommendedAction[]
  routeToTeam: string | null
  /** Frozen Playbook owner; absent on Diagnosis contracts before 1.7. */
  sourcePlaybookOwner?: string | null
  /** Exact immutable authority used by Diagnosis 1.8 deterministic routes. */
  sourcePlaybookVersionRef?: PlaybookVersionRef | null
  transfers: unknown[]
  actionOutcomes: unknown[]
  closure: ClosureRecord | null
  knowledgeCandidates: KnowledgeCandidate[]
  timeline: TimelineEvent[]
  /** D14 timing snapshot; legacy 1.3/1.4 rows carry all-null values. */
  timings: NorthStarTimings
  rehearsal: boolean
  /** True until real evidence bindings and thresholds are live-verified. */
  fixtureMode: boolean
  /** Always false; the contract rejects an enabled write executor outright. */
  writeExecutionEnabled: boolean
  warnings: string[]
}

export interface StoredDiagnosis {
  diagnosis: Diagnosis
  version: number
  /** False when a retry hit the five-minute deduplication bucket. */
  created: boolean
}

export interface DiagnosisSummary {
  diagnosisId: string
  caseId: string
  system: string
  errorCode: string | null
  service: string
  status: DiagnosisStatus
  /** Null only for 1.3/1.4 rows whose v4 route semantics were never persisted. */
  investigationMode: InvestigationMode | null
  routeAuthority: RouteAuthority | null
  routeSemanticsProvenance: RouteSemanticsProvenance
  rehearsal: boolean
  /** Immutable pilot cohort admitted by the backend at Diagnosis creation. */
  pilotPlanVersion?: number | null
  version: number
  createTime: string
  updateTime: string
}

/** Why a signal did or did not contribute — see CriterionOutcome on the server. */
export type CriterionOutcome = 'SATISFIED' | 'EXCLUDED' | 'UNEVALUATED'

export interface CriterionEvaluation {
  signal: string
  sourceRequestId: string
  description: string
  kind: string
  /** The rule as authored, e.g. `count ≥ 1`. */
  expression: string
  /** The same rule with observed values filled in, rendered server-side. */
  substitution: string
  outcome: CriterionOutcome
  evidenceStatus: EvidenceStatus
}

export interface RuleEvaluation {
  ruleId: string
  requiredSignals: string[]
  rootCause: string
  confidence: Confidence
  fired: boolean
  /** Required signals whose criteria evaluated false — genuinely ruled out. */
  unsatisfiedByExclusion: string[]
  /** Required signals whose evidence never arrived — still untested. */
  unsatisfiedByGap: string[]
  /** Required signals no criterion produces at all — a gap in the SOP. */
  undefinedSignals: string[]
}

/**
 * 这次调查动用了什么，以及刻意没有动用什么。
 *
 * The negatives are not decoration: this product's safety argument is a set of
 * things that did not happen, and a view listing only participants lets the
 * reader assume the rest — more generously than the truth.
 */
export interface InvestigationProvenance {
  diagnosisId: string
  knowledge: {
    selectorKey: string
    title: string | null
    playbookId: string | null
    playbookVersion: number | null
    ownerTeam: string | null
    /** 手写夹具 vs 真实归纳；读不到冻结版本时为 null，不猜 */
    origin: string | null
    operational: boolean
    readable: boolean
    note: string | null
  }
  collectors: Array<{
    requestId: string
    signalKind: string
    adapter: string
    status: 'NORMAL' | 'ANOMALY' | 'MISSING'
    answered: boolean
    /** null = 本路径不维护引用清单，与「没有支撑结论」不是一回事 */
    cited: boolean | null
    collectedAt: string | null
  }>
  reasoning: {
    routeMode: string
    investigationMode: InvestigationMode
    routeAuthority: string
    conclusionType: string
    modelInvoked: boolean
    modelIdentity: string | null
    signalsSatisfied: number
    derivationRebuildable: boolean
  }
  abstentions: Array<{ capability: string; reason: string }>
}

export interface DiagnosisDerivation {
  diagnosisId: string
  sopKey: string
  /** False when the SOP changed since, so the chain no longer describes what happened. */
  faithful: boolean
  note: string | null
  criteria: CriterionEvaluation[]
  rules: RuleEvaluation[]
}

/** Formal workbench projections — the browser renders these and does not infer conclusions. */
export type ConclusionType = 'LOCATED' | 'EXCLUDED' | 'HYPOTHESIS' | 'INSUFFICIENT_EVIDENCE'
export type BlastRadius = 'SINGLE_CUSTOMER' | 'MULTI_CUSTOMER' | 'SYSTEM_WIDE' | 'UNKNOWN'
export type InvestigationMode = 'ERROR_CODE_PLAYBOOK' | 'SCENARIO_PLAYBOOK' | 'OPEN_DISCOVERY'
export type RouteAuthority = 'EXPLICIT' | 'RULE_MATCHED' | 'MODEL_PROPOSED'
export type RouteSemanticsProvenance = 'PERSISTED' | 'LEGACY_DERIVED'
export type EvidenceStepTone = 'NORMAL' | 'ANOMALY' | 'EXCLUDED' | 'UNEVALUATED'
export type EvidenceStepKind = 'EVIDENCE' | 'CRITERION'
export type DraftReviewStatus = 'DRAFT' | 'CANDIDATE'

export interface ImpactView {
  functionScope: string
  affectedCustomers: number | null
  affectedUsers: number | null
  blastRadius: BlastRadius
  evidenceRefs: string[]
  observedAt: string | null
  note: string
}

export interface ProjectionNextStep {
  label: string
  text: string
  capabilityBoundary: string | null
}

export interface NorthStarTimings {
  reportedAt: string | null
  readyAt: string | null
  conclusionAt: string | null
  handoffAt: string | null
  intakeCost: string | null
  investigateCost: string | null
  adoptCost: string | null
}

export interface BusinessSummary {
  diagnosisId: string
  conclusionType: ConclusionType
  headline: string
  narrative: string
  confidence: Confidence
  problem: string
  impact: ImpactView
  nextStep: ProjectionNextStep
  status: DiagnosisStatus
  timings: NorthStarTimings
  fixtureMode: boolean
}

export interface CallChainHop {
  hopId: string
  service: string
  duration: string
  anomalous: boolean
}

export interface CallChainView {
  psId: string | null
  hops: CallChainHop[]
  emptyReason: string | null
  blastRadius: BlastRadius
}

export interface ProjectionEvidenceStep {
  kind: EvidenceStepKind
  at: string | null
  title: string
  detail: string
  ref: string
  tone: EvidenceStepTone
}

export type InvestigationStageKey =
  | 'INCIDENT'
  | 'PLAYBOOK_ROUTE'
  | 'EVIDENCE_CONTRACT'
  | 'ADAPTER_SELECTION'
  | 'EVIDENCE_COLLECTION'
  | 'CRITERION_EVALUATION'
  | 'CONCLUSION'
export type InvestigationStageStatus = 'COMPLETED' | 'PARTIAL' | 'STOPPED' | 'UNRECORDED'
export type AdapterAttemptHistoryStatus = 'FINAL_RESULT_ONLY'
export type InvestigationStopReasonCode =
  | 'CONCLUSION_RECORDED'
  | 'EVIDENCE_MISSING'
  | 'SOURCE_UNAVAILABLE'
  | 'ABSTAINED'
  | 'UNRECORDED'
export type RelationNodeKind = 'EVIDENCE' | 'CRITERION' | 'RULE' | 'CONCLUSION'
export type RelationType = 'SUPPORTS' | 'REFUTES' | 'BLOCKS' | 'CITES'

export interface InvestigationTraceField {
  label: string
  value: string
}

export interface InvestigationStageView {
  sequence: number
  key: InvestigationStageKey
  title: string
  status: InvestigationStageStatus
  summary: string
  startedAt: string | null
  completedAt: string | null
  duration: string | null
  fields: InvestigationTraceField[]
  evidenceRefs: string[]
}

export interface EvidenceContractView {
  requestId: string
  signalKind: string
  purpose: string
  target: Record<string, unknown>
  window: string
  required: boolean
}

export interface AdapterAttemptView {
  evidenceRef: string
  requestId: string
  signalKind: string
  adapterSource: string
  status: EvidenceStatus
  summary: string
  query: string
  observed: Record<string, unknown>
  collectedAt: string | null
  duration: string | null
  historyStatus: AdapterAttemptHistoryStatus
}

export interface InvestigationStopReasonView {
  code: InvestigationStopReasonCode
  message: string
  stoppedAt: string | null
  evidenceRefs: string[]
}

export interface RelationNode {
  nodeId: string
  kind: RelationNodeKind
  label: string
  detail: string
  status: string
  ref: string
}

export interface RelationEdge {
  edgeId: string
  fromNodeId: string
  toNodeId: string
  relation: RelationType
  label: string
}

export interface EvidenceRelationView {
  available: boolean
  nodes: RelationNode[]
  edges: RelationEdge[]
  emptyReason: string | null
}

export interface InvestigationTraceView {
  diagnosisId: string
  investigationDuration: string | null
  stages: InvestigationStageView[]
  evidenceContracts: EvidenceContractView[]
  adapterAttempts: AdapterAttemptView[]
  stopReason: InvestigationStopReasonView
  evidenceRelation: EvidenceRelationView
}

export interface ContrastView {
  available: boolean
  featureCode: string | null
  failedRequests: ComparisonGroupView | null
  normalRequests: ComparisonGroupView | null
  note: string
  evidenceRefs: string[]
}

export interface ComparisonGroupView {
  totalRequests: number | string
  requestsWithFeature: number | string
}

export interface DraftView {
  draftId: string | null
  title: string
  steps: string[]
  emptyReason: string | null
  reviewStatus: DraftReviewStatus
  stateNote: string
}

export interface ScenarioAffordance {
  scenarioKey: string
  required: boolean
}

export interface DeveloperEvidenceView {
  diagnosisId: string
  investigationMode: InvestigationMode
  routeAuthority: RouteAuthority
  routeSemanticsProvenance: RouteSemanticsProvenance
  playbookRef: string | null
  /** Null only when no Playbook owns this Diagnosis. */
  knowledgeEvidenceGrade: KnowledgeEvidenceGrade | null
  /** Keyed scenario capabilities; a new scenario adds a row, not a boolean. */
  scenarioAffordances: ScenarioAffordance[]
  callChain: CallChainView
  steps: ProjectionEvidenceStep[]
  investigationTrace: InvestigationTraceView
  contrast: ContrastView
  draft: DraftView
  capabilityLimits: string[]
  fixtureMode: boolean
}

export interface DiagnosisExperienceProjection {
  businessSummary: BusinessSummary
  developerEvidence: DeveloperEvidenceView
}

export type GuanceReadinessStatus =
  | 'DISABLED'
  | 'CONFIGURATION_INCOMPLETE'
  | 'UNAUTHORIZED'
  | 'READY_FOR_VALIDATION'
  | 'CANONICAL_SIGNALS_OBSERVED'
export type GuanceCredentialState = 'NOT_INSPECTED' | 'MISSING' | 'CONFIGURED'
export type GuanceSignalStatus =
  | 'NOT_ROUTED'
  | 'UNAUTHORIZED'
  | 'INVALID_BINDING'
  | 'READY_FOR_VALIDATION'
  | 'CANONICAL_RESULT_OBSERVED'

export interface GuanceSignalReadiness {
  signalKind: string
  routedToGuance: boolean
  status: GuanceSignalStatus
  bindingRef: string
  lastObservedAt: string | null
  detail: string
}

/** Secret-free, non-probing readiness for one workspace-owned source asset. */
export interface GuanceEvidenceReadiness {
  system: string
  service: string
  status: GuanceReadinessStatus
  adapterEnabled: boolean
  endpointConfigured: boolean
  credentialState: GuanceCredentialState
  uniqueAssetAuthorized: boolean
  signals: GuanceSignalReadiness[]
  blockers: string[]
}

export type OpenDiscoveryReadinessStatus =
  | 'DISABLED'
  | 'BLOCKED'
  | 'READY_FOR_REHEARSAL'
  | 'READY_FOR_BOUNDED_FALLBACK'

export interface OpenDiscoveryPlanSummary {
  scenarioKey: string
  system: string
  enabled: boolean
  visibleForRequestedSystem: boolean
  permittedPlatforms: string[]
  includesTrueSource: boolean
}

/** Secret-free readiness for the OPEN_DISCOVERY / miss-path night-time fallback. */
export interface OpenDiscoveryReadiness {
  status: OpenDiscoveryReadinessStatus
  agentEnabled: boolean
  configuredAgentId: string | number
  configuredAgentName?: string
  agentBindingSource?: 'WORKSPACE' | 'CONFIG' | 'NONE' | string
  agentReady: boolean
  configuredPlanCount: number
  visiblePlanCount: number
  trueSourcePermitted: boolean
  plans: OpenDiscoveryPlanSummary[]
  blockers: string[]
  nextAction: string
}

export interface OpenDiscoveryAgentBinding {
  workspaceId: string | number
  agentId: string | number
  agentName?: string | null
  source: 'WORKSPACE' | 'CONFIG' | 'NONE' | string
  boundBy?: string | null
  boundAt?: string | null
  blockers: string[]
  ready: boolean
}

export interface TroubleshootingPilotModuleScope {
  system: string
  service: string
}

export interface TroubleshootingPilotMember {
  /** Backend-issued Snowflake identifier; keep as a string when editing. */
  userId: string | number
  username?: string | null
  nickname?: string | null
  displayName: string
  workspaceRole?: string | null
}

/** Latest immutable first-wave pilot declaration for the current Workspace. */
export interface TroubleshootingPilotPlan {
  workspaceId: string | number
  configured: boolean
  enabled: boolean
  version: number
  name?: string | null
  modules: TroubleshootingPilotModuleScope[]
  secondLine?: TroubleshootingPilotMember | null
  thirdLine?: TroubleshootingPilotMember | null
  sourceOwner?: TroubleshootingPilotMember | null
  changedBy?: string | null
  changedAt?: string | null
  changeReason?: string | null
  blockers: string[]
}

export interface DeclareTroubleshootingPilotPlanRequest {
  name: string
  modules: TroubleshootingPilotModuleScope[]
  secondLineUserId: string | number
  thirdLineUserId: string | number
  sourceOwnerUserId: string | number
  enabled: boolean
  expectedVersion: number
  reason: string
}

export type EvidenceRouteOrigin = 'WORKSPACE' | 'DEPLOYMENT' | 'UNCONFIGURED'

export interface EvidenceCatalogSource {
  platform: string
  status: string
  verified: boolean
  endpointStatus: string
  credentialStatus: string
  supportedSignals: string[]
  detail: string
}

export interface EvidenceQueryEndpoint {
  operationKind: string
  method: string
  path: string
  qtype: string
}

export interface EvidenceQueryParameter {
  name: string
  source: string
  required: boolean
  description: string
}

export interface EvidenceQueryBudget {
  queryCount: number
  maxRows: number
  requestLimit: number
  timeoutMs: number
  maxPointCount: number | null
  intervalSeconds: number | null
  seriesLimit: number | null
  alignTime: boolean | null
  disableSampling: boolean | null
  timeZone: string | null
}

export interface EvidenceCatalogRoute {
  origin: EvidenceRouteOrigin
  platforms: string[]
  explicitlyDisabled: boolean
  updatedBy: string | null
  reason: string | null
  updatedAt: string | null
}

export interface EvidenceCatalogBinding {
  status: string
  bindingRef: string
  lastObservedAt: string | null
  detail: string
}

export interface EvidenceQueryContract {
  contractRef: string
  signalKind: string
  scenario: string
  question: string
  summary: string
  adapter: string
  namespace: string
  fixedConditions: string[]
  endpoint: EvidenceQueryEndpoint
  parameters: EvidenceQueryParameter[]
  canonicalOutputs: string[]
  budget: EvidenceQueryBudget
  route: EvidenceCatalogRoute
  binding: EvidenceCatalogBinding
  runnable: boolean
  blockers: string[]
}

export interface EvidenceCatalogAcceptance {
  status: string
  currentBindingFingerprint: string | null
  acceptedBy: string | null
  acceptedAt: string | null
  blockers: string[]
}

export interface EvidenceCatalogModule {
  service: string
  status: string
  runnableContracts: number
  blockers: string[]
  acceptance: EvidenceCatalogAcceptance
  contracts: EvidenceQueryContract[]
}

export interface EvidenceCatalogSystem {
  system: string
  modules: EvidenceCatalogModule[]
}

/** Server-owned, secret-free directory; it contains no DQL, endpoint host or credential. */
export interface EvidenceQueryCatalog {
  contractVersion: 'evidence-query-catalog.v1'
  workspaceId: number
  sources: EvidenceCatalogSource[]
  systems: EvidenceCatalogSystem[]
}

export interface EvidenceContractTrialRequest {
  system: string
  service: string
  contractRef: string
  parameters: Record<string, string>
  window?: string
  occurredAt?: string
}

/** Secret-free audit projection. Query terms, raw rows, DQL and credentials are never returned. */
export interface EvidenceContractTrial {
  trialId: string
  workspaceId: number
  system: string
  service: string
  contractRef: string
  signalKind: string
  assetId: string
  assetVersion: number
  status: 'OBSERVED' | 'NO_EVIDENCE' | 'FAILED'
  stopReason: 'COMPLETED' | 'NO_CANONICAL_EVIDENCE' | 'SOURCE_QUERY_FAILED'
  source: string
  canonicalFields: string[]
  durationMs: number
  actor: string
  completedAt: string
  warning: string
}

export interface EvidenceRoutePlatformState {
  platform: string
  available: boolean
  detail: string
}

export interface EvidenceRouteDeclaration {
  system: string
  signalKind: string
  platforms: string[]
  platformStates: EvidenceRoutePlatformState[]
  updatedBy: string
  reason: string
  updatedAt: string
}

export interface DeclareEvidenceRouteRequest {
  system: string
  signalKind: string
  platforms: string[]
  reason: string
}

export type ObservabilityAssetOrigin = 'WORKSPACE' | 'DEPLOYMENT'

export interface ObservabilityAsset {
  assetId: string | null
  origin: ObservabilityAssetOrigin
  workspaceId: number
  system: string
  service: string
  displayName: string
  platform: string
  environment: string | null
  region: string | null
  cluster: string | null
  namespace: string | null
  enabled: boolean
  signalBindings: Record<string, string>
  parameters: Record<string, string>
  version: number
  changedBy: string | null
  reason: string | null
  changedAt: string | null
}

export interface ObservabilityAssetContractOption {
  contractRef: string
  signalKind: string
  scenario: string
  question: string
  summary: string
  requiredAssetParameters: string[]
}

export type EvidenceContractScopeType = 'GENERIC' | 'SYSTEM' | 'MODULE'

export interface EvidenceContractView {
  contractRef: string
  signalKind: string
  scopeType: EvidenceContractScopeType | string
  system: string
  service: string
  scenario: string
  question: string
  summary: string
  namespace: string
  maxRows: number
  fixedConditions: string[]
  requiredAssetParameters: string[]
  origin: 'DEPLOYMENT' | 'WORKSPACE' | string
  enabled: boolean
  version: number
  queryTemplate?: string | null
}

export interface EvidenceContractCatalog {
  workspaceId: number
  contracts: EvidenceContractView[]
}

export interface DeclareEvidenceContractRequest {
  contractRef: string
  signalKind: string
  scopeType: EvidenceContractScopeType
  system?: string
  service?: string
  scenario: string
  question: string
  summary?: string
  namespace?: string
  maxRows?: number
  queryTemplate: string
  fixedConditions?: string[]
  requiredAssetParameters?: string[]
  fieldAliases?: Record<string, string>
  enabled: boolean
  expectedVersion?: number
  reason: string
}

export interface ObservabilityAssetCatalog {
  workspaceId: number
  assets: ObservabilityAsset[]
  contracts: ObservabilityAssetContractOption[]
}

export interface DeclareObservabilityAssetRequest {
  system: string
  service: string
  displayName: string
  platform: 'guance'
  environment: string
  region?: string
  cluster?: string
  namespace?: string
  enabled: boolean
  signalBindings: Record<string, string>
  parameters: Record<string, string>
  expectedVersion?: number
  reason: string
}

export type GuanceEvidenceAcceptanceStatus =
  | 'BLOCKED'
  | 'NOT_ACCEPTED'
  | 'STALE'
  | 'ACCEPTED'

export interface GuanceEvidenceAcceptanceChecklist {
  measurementAndFieldsVerified: boolean
  indexVerified: boolean
  psIdJoinVerified: boolean
  timestampUnitVerified: boolean
  timeWindowVerified: boolean
  dqlLatencyReviewed: boolean
  legacyRouteConflictReviewed: boolean
}

export interface GuanceEvidenceAcceptanceFacts {
  matchCount: number
  traceEntries: number
  psIdFingerprint: string
  logSearchDurationMs: number
  logTraceDurationMs: number
  totalDurationMs: number
  observedAt: string
}

/** Immutable owner attestation; no search key, PS ID, DQL, credential or raw row. */
export interface GuanceEvidenceAcceptance {
  acceptanceId: string
  system: string
  service: string
  bindingFingerprint: string
  checklist: GuanceEvidenceAcceptanceChecklist
  validation: GuanceEvidenceAcceptanceFacts
  acceptedBy: string
  acceptedAt: string
}

export interface GuanceEvidenceAcceptanceView {
  status: GuanceEvidenceAcceptanceStatus
  system: string
  service: string
  currentBindingFingerprint: string | null
  acceptance: GuanceEvidenceAcceptance | null
  blockers: string[]
}

export interface GuanceRecordingTarget {
  targetId: string
  system: string
  service: string
  selectorKey: string
  candidateReference: string
  candidateFingerprint: string
  requiredEvidenceRequestId: string
  requestFingerprint: string
  searchTerm: string
  window: string
  bindingRefs: Record<'log_search' | 'log_trace_bundle' | 'contrast_sample', string>
}

/** Server-owned T7 batch identities. Candidate bodies and Guance queries stay server-side. */
export interface GuanceRecordingTargetCatalogView {
  contractVersion: 't7-guance-recording-target-catalog.v1'
  system: string
  service: string
  catalogFingerprint: string
  frozenTargetCount: number
  executableTargetCount: number
  targets: GuanceRecordingTarget[]
  asOfEpochSeconds: number
  blockers: string[]
}

export interface AcceptGuanceEvidenceRequest extends EvidenceChainPreviewRequest {
  checklist: GuanceEvidenceAcceptanceChecklist
}

export type GuanceValidationStage = 'BLOCKED' | 'CANONICAL_CHAIN_OBSERVED'
export type GuanceValidationStepStatus =
  | 'NOT_RUN'
  | 'BLOCKED'
  | 'CANONICAL_RESULT_OBSERVED'

export interface GuanceValidationStep {
  signalKind: string
  status: GuanceValidationStepStatus
  evidenceRef: string
  detail: string
  durationMs: number | null
  collectedAt: string | null
}

/** Structural outcome only; raw source rows and DQL never cross this boundary. */
export interface GuanceEvidenceValidationReport {
  stage: GuanceValidationStage
  readiness: GuanceEvidenceReadiness
  matchCount: number | null
  psId: string | null
  traceEntries: number | null
  totalDurationMs: number
  steps: GuanceValidationStep[]
  completedAt: string
  warnings: string[]
}

export type GuanceSpinePreviewStage =
  | 'BLOCKED'
  | 'CORE_CHAIN_OBSERVED'
  | 'FULL_SPINE_OBSERVED'
export type GuanceSpinePreviewStepStatus =
  | 'NOT_RUN'
  | 'MISSING'
  | 'CANONICAL_RESULT_OBSERVED'

export interface GuanceSpinePreviewStep {
  signalKind: string
  status: GuanceSpinePreviewStepStatus
  evidenceRef: string
  collectedAt: string | null
}

export interface GuanceSpineContrast {
  available: boolean
  discriminatingFeature: string | null
  failureSampleCount: number | string
  failureMatchCount: number | string
  successSampleCount: number | string
  successMatchCount: number | string
  failureRate: number
  successRate: number
  rateDelta: number
}

/** Application-side wall time; null means the stage was not measured or not reached. */
export interface EvidenceSpineTimings {
  logSearchDurationMs: number | null
  logTraceDurationMs: number | null
  contrastDurationMs: number | null
  compressionDurationMs: number | null
}

/** Guance-only, model-free and secret-free projection of the shared Evidence Spine. */
export interface GuanceEvidenceSpinePreview {
  stage: GuanceSpinePreviewStage
  readiness: GuanceEvidenceReadiness
  matchCount: number | null
  psId: string | null
  traceEntries: number | null
  serviceSequence: string[]
  anomalyCount: number
  traceElapsedMs: number | null
  contrast: GuanceSpineContrast
  sourceRequestCount: number
  totalDurationMs: number
  timings: EvidenceSpineTimings
  steps: GuanceSpinePreviewStep[]
  completedAt: string
  warnings: string[]
}

export type DeploymentTopologyAnalysisStatus =
  | 'NO_PROBES_CONFIGURED'
  | 'INSUFFICIENT_EVIDENCE'
  | 'PARTIAL_OBSERVATION'
  | 'NETWORK_PROBLEM_DETECTED'
  | 'NO_PROBLEM_OBSERVED'

export interface DeploymentTopologyAssetSummary {
  topologyId: string
  name: string
  system: string
  systemLabel: string
  schemaVersion: string
  exportedAt: string
  nodeCount: number
  linkCount: number
  configuredProbeNodes: number
  importedBy: string
  importedAt: string
}

export interface DeploymentTopologyImportResult {
  topology: DeploymentTopologyAssetSummary
  created: boolean
}

export type DeploymentTopologyObservationStatus =
  | 'HEALTHY'
  | 'FAILED'
  | 'UNAVAILABLE'
  | 'IDENTITY_MISMATCH'

export interface DeploymentTopologySopSummary {
  nodeCount: number
  linkCount: number
  configuredProbeNodes: number
  observedProbeNodes: number
  healthyProbeNodes: number
  failingProbeNodes: number
  unavailableProbeNodes: number
}

export interface DeploymentTopologyNodeObservation {
  nodeKey: string
  label: string
  type: string
  targetUrl: string
  probeName: string
  window: string
  status: DeploymentTopologyObservationStatus
  statusCode: number | null
  observedTargetUrl: string
  observedProbeName: string
  evidenceRef: string
  detail: string
  collectedAt: string | null
}

export interface DeploymentTopologySuspectLink {
  source: string
  target: string
  reason: string
}

/** Safe read result. It never contains an API key, DQL, or raw Guance rows. */
export interface DeploymentTopologySopResult {
  schemaVersion: string
  system: string
  systemLabel: string
  snapshotExportedAt: string
  signalKind: 'synthetic_probe'
  status: DeploymentTopologyAnalysisStatus
  summary: DeploymentTopologySopSummary
  observations: DeploymentTopologyNodeObservation[]
  suspectLinks: DeploymentTopologySuspectLink[]
  unconfiguredNodeKeys: string[]
  warnings: string[]
  completedAt: string
  modelCalled: false
  persisted: boolean
}

/** Immutable topology evidence run owned by one troubleshooting Diagnosis. */
export interface TopologyProbeEvidenceRun {
  runId: string
  diagnosisId: string
  topologyId: string
  scenarioKey: 'deployment_topology_probe'
  toolKey: 'topology_synthetic_probe'
  result: DeploymentTopologySopResult
  startedAt: string
  completedAt: string
  actorRef: string
}

export type EvaluationSampleSourcePlatform = 'GUANCE' | 'RECORDED_REPLAY'
export type EvaluationSampleReferenceStatus = 'EVIDENCE_CAPTURED' | 'READY_FOR_EVALUATION'
export type EvaluationExpectedDisposition = 'DRAFT' | 'ABSTAIN'
export type EvaluationHumanBaselineBasis = 'MEASURED' | 'ESTIMATED'

export interface EvaluationHumanBaseline {
  minutesToLocate: number
  basis: EvaluationHumanBaselineBasis
  note: string
}

export interface EvaluationEvidenceSnapshot {
  stage: Exclude<GuanceSpinePreviewStage, 'BLOCKED'>
  fixtureMode: boolean
  matchCount: number
  psId: string
  traceEntries: number
  serviceSequence: string[]
  anomalyCount: number
  traceElapsedMs: number
  contrast: GuanceSpineContrast
  sourceRequestCount: number
  totalDurationMs: number
  timings: EvidenceSpineTimings
  steps: GuanceSpinePreviewStep[]
  completedAt: string
}

export interface EvaluationReferenceSolution {
  referenceId: string
  scenarioKey: string
  requiredStepIntents: string[]
  forbiddenStepIntents: string[]
  orderingConstraints: Array<{ beforeIntent: string; afterIntent: string }>
  requiredEvidenceKinds: string[]
}

export interface EvaluationOutcomeSnapshot {
  outcome: ClosureOutcome
  summary: string
  recoveryVerified: boolean
  closedAt: string
}

/** Secret-free historical sample; source lookup keys and raw evidence never appear here. */
export interface EvidenceEvaluationSample {
  sampleId: string
  sampleKey: string
  captureIdentityKey: string
  captureRevision: number
  diagnosisId: string
  system: string
  service: string
  scenarioKey: string
  sourcePlatform: EvaluationSampleSourcePlatform
  evidence: EvaluationEvidenceSnapshot
  /** SHA-256 of the exact bounded model input; null only for legacy V181 samples. */
  modelInputHash: string | null
  /** Frozen evidence anchor used for a reproducible source rerun. */
  evidenceOccurredAt: string | null
  diagnosisFixtureMode: boolean
  referenceStatus: EvaluationSampleReferenceStatus
  referenceSolution: EvaluationReferenceSolution | null
  expectedDisposition: EvaluationExpectedDisposition | null
  /** Optional human time before MateClaw; measured and estimated cohorts never mix. */
  humanBaseline: EvaluationHumanBaseline | null
  outcome: EvaluationOutcomeSnapshot | null
  version: number
  capturedBy: string
  finalizedBy: string | null
  capturedAt: string
  finalizedAt: string | null
}

export interface StoredEvidenceEvaluationSample {
  sample: EvidenceEvaluationSample
  created: boolean
}

export interface EvaluationSampleSummary {
  total: number
  guance: number
  recordedReplay: number
  evidenceCaptured: number
  readyForEvaluation: number
  fullSpineObserved: number
  coreChainObserved: number
  linkedFixtureDiagnoses: number
  timingMeasuredSamples: number
  guanceLatency: EvaluationLatencySummary
  recordedReplayLatency: EvaluationLatencySummary
  minimumEvaluationTarget: number
  targetRangeMax: number
}

/** Source-isolated descriptive percentiles; this is not an acceptance verdict. */
export interface EvaluationLatencySummary {
  sampleCount: number
  evidenceP50Ms: number | null
  evidenceP95Ms: number | null
  compressionP50Ms: number | null
  compressionP95Ms: number | null
  totalP50Ms: number | null
  totalP95Ms: number | null
}

export interface EvidenceEvaluationSampleLedger {
  samples: EvidenceEvaluationSample[]
  summary: EvaluationSampleSummary
}

export interface CaptureEvaluationSampleRequest {
  diagnosisId: string
  scenarioKey: string
  searchTerm: string
  window: string
}

export interface CaptureRecordedReplayEvaluationSampleRequest {
  diagnosisId: string
}

export interface RecordedReplayEvaluationCapability {
  available: boolean
  reasonCode: string
  reason: string
  scenarioKey: string | null
  searchTerm: string | null
  window: string | null
}

export interface FinalizeEvaluationSampleReferenceRequest {
  expectedVersion: number
  requiredStepIntents: string[]
  forbiddenStepIntents: string[]
  expectedDisposition: EvaluationExpectedDisposition
  humanBaseline: EvaluationHumanBaseline | null
}

export interface EvaluationNorthStarCohort {
  count: number
  p50Minutes: number | null
  p95Minutes: number | null
}

/** Descriptive pilot comparison. It deliberately contains no saved-time verdict. */
export interface EvaluationNorthStarComparison {
  sampleCount: number
  withHumanBaseline: number
  measured: EvaluationNorthStarCohort
  estimated: EvaluationNorthStarCohort
  machineP50Ms: number | null
  machineP95Ms: number | null
  machineRunCount: number
  caveats: string[]
}

export type BaselineEvaluationStatus =
  | 'MODEL_REJECTED'
  | 'ABSTAINED'
  | 'VALIDATION_REJECTED'
  | 'SCORED'
export type BaselineActualDisposition = 'NONE' | 'DRAFT' | 'ABSTAIN'
export type BaselineClassification =
  | 'HELPFUL'
  | 'UNHELPFUL'
  | 'HARMFUL_BLOCKED'
  | 'TECHNICAL_FAILURE'

export interface BaselineValidationSnapshot {
  executed: boolean
  valid: boolean
  errorCodes: string[]
}

export interface BaselineQualitySnapshot {
  expectedDisposition: EvaluationExpectedDisposition
  actualDisposition: BaselineActualDisposition
  classification: BaselineClassification
  citationComplete: boolean | null
  requiredIntentCoverage: number | null
  missingStepIntents: string[]
  forbiddenStepIntentsPresent: string[]
  orderingViolations: string[]
  missingEvidenceKinds: string[]
  abstainAssessmentCodes: string[]
  dangerousProposalDetected: boolean
}

export interface BaselineModelSnapshot {
  provider: string
  modelName: string
  modelConfigVersion: string
  calledAt: string
  invocationCount: number
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
}

/** Candidate-free, immutable single-Agent baseline fact. Draft text is never returned. */
export interface BaselineEvaluationRun {
  runId: string
  runKey: string
  sampleId: string
  diagnosisId: string
  sampleVersion: number
  sourcePlatform: EvaluationSampleSourcePlatform
  evidenceFixtureMode: boolean
  diagnosisFixtureMode: boolean
  evidenceStage: Exclude<GuanceSpinePreviewStage, 'BLOCKED'>
  modelInputHash: string
  status: BaselineEvaluationStatus
  modelErrorCodes: string[]
  validation: BaselineValidationSnapshot
  quality: BaselineQualitySnapshot
  model: BaselineModelSnapshot
  evidenceDurationMs: number
  modelDurationMs: number
  composedTotalDurationMs: number
  executedBy: string
  executedAt: string
}

export interface StoredBaselineEvaluationRun {
  run: BaselineEvaluationRun
  created: boolean
}

export interface BaselineCohortMetrics {
  runCount: number
  helpful: number
  unhelpful: number
  harmfulBlocked: number
  technicalFailure: number
  modelP50Ms: number | null
  modelP95Ms: number | null
  composedTotalP50Ms: number | null
  composedTotalP95Ms: number | null
  tokenMeasuredRuns: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  quality: BaselineQualityMetrics
}

export interface BaselineQualityMetrics {
  citationAssessedRuns: number
  citationCompleteRuns: number
  coverageAssessedRuns: number
  coverageP50: number | null
  coverageMin: number | null
  fullCoverageRuns: number
  abstentions: number
  cleanAbstentions: number
  abstainFailureCounts: Record<string, number>
  dangerousProposalRuns: number
  confidenceAssessedRuns: number
  highConfidenceRuns: number
  highConfidenceErrorRuns: number
}

export interface BaselineSourceMetrics {
  runCount: number
  evidenceFixtureRuns: number
  realDiagnosis: BaselineCohortMetrics
  fixtureDiagnosis: BaselineCohortMetrics
}

export interface BaselineEvaluationSummary {
  total: number
  scored: number
  abstained: number
  modelRejected: number
  validationRejected: number
  guance: BaselineSourceMetrics
  recordedReplay: BaselineSourceMetrics
}

export interface BaselineEvaluationLedger {
  runs: BaselineEvaluationRun[]
  summary: BaselineEvaluationSummary
}

export interface RunBaselineEvaluationRequest {
  expectedSampleVersion: number
  searchTerm: string
  window: string
}
