import type {
  EvidenceChainPreviewRequest,
  GuanceEvidenceAcceptanceView,
} from '@/api'

export interface GuanceOnboardingScope {
  workspaceId: string
  system: string
  service: string
}

export interface GuanceOnboardingGuide {
  externalConfig: string
  runtimeEnvironment: string
}

export interface GuanceOnboardingValidationPayload {
  request: EvidenceChainPreviewRequest
  ownerAcceptance: GuanceEvidenceAcceptanceView
}

export type GuanceValidationOrigin = 'DIAGNOSIS' | 'ONBOARDING'

export interface GuanceValidationSessionSnapshot {
  sessionVersion: number
  origin: GuanceValidationOrigin | null
  request: EvidenceChainPreviewRequest
}

const SAFE_RESOURCE_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$/
const SAFE_T7_SEARCH_TERM = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
const SAFE_WORKSPACE_ID = /^[1-9][0-9]{0,18}$/
const MAX_JAVA_LONG = 9223372036854775807n

export function guanceOnboardingScopeKey(scope: GuanceOnboardingScope): string {
  return [scope.workspaceId, scope.system, scope.service]
    .map((value) => value.trim())
    .join('\u0000')
}

export function isSafeGuanceSearchTerm(searchTerm: string): boolean {
  return SAFE_T7_SEARCH_TERM.test(searchTerm.trim())
}

export function sameEvidenceChainLookup(
  left: EvidenceChainPreviewRequest,
  right: EvidenceChainPreviewRequest,
): boolean {
  return left.system === right.system
    && left.service === right.service
    && left.searchTerm === right.searchTerm
    && left.window === right.window
    && left.occurredAt === right.occurredAt
}

export function canAttachGuanceResultToDiagnosis(
  origin: GuanceValidationOrigin | null,
  currentLookup: EvidenceChainPreviewRequest | null,
  request: EvidenceChainPreviewRequest,
): boolean {
  return origin === 'DIAGNOSIS'
    && Boolean(currentLookup && sameEvidenceChainLookup(currentLookup, request))
}

export function isActiveGuanceValidationSession(
  requested: GuanceValidationSessionSnapshot,
  current: GuanceValidationSessionSnapshot,
  dialogOpen: boolean,
): boolean {
  return dialogOpen
    && requested.sessionVersion === current.sessionVersion
    && requested.origin === current.origin
    && sameEvidenceChainLookup(requested.request, current.request)
}

export function guanceOnboardingScopeErrors(scope: GuanceOnboardingScope): string[] {
  const errors: string[] = []
  const workspaceId = scope.workspaceId.trim()
  if (!SAFE_WORKSPACE_ID.test(workspaceId) || BigInt(workspaceId || '0') > MAX_JAVA_LONG) {
    errors.push('当前 Workspace ID 不可用')
  }
  if (!SAFE_RESOURCE_IDENTIFIER.test(scope.system.trim())) {
    errors.push('system 必须是安全资源标识符')
  }
  if (!SAFE_RESOURCE_IDENTIFIER.test(scope.service.trim())) {
    errors.push('service 必须是安全资源标识符')
  }
  return errors
}

export function buildGuanceOnboardingGuide(
  scope: GuanceOnboardingScope,
): GuanceOnboardingGuide {
  const errors = guanceOnboardingScopeErrors(scope)
  if (errors.length) throw new Error(errors.join('；'))

  const workspaceId = scope.workspaceId.trim()
  const system = scope.system.trim()
  const service = scope.service.trim()
  return {
    externalConfig: [
      '# 外部运行配置：只登记资产授权和已核实 binding，不要写 API Key',
      'mateclaw:',
      '  troubleshooting:',
      '    evidence:',
      '      guance:',
      '        asset-bindings:',
      `          - workspace-id: ${workspaceId}`,
      `            system: ${system}`,
      `            service: ${service}`,
      '            signal-bindings:',
      '              log_search: <verified-log-search-binding>',
      '              log_trace_bundle: <verified-log-trace-bundle-binding>',
    ].join('\n'),
    runtimeEnvironment: [
      'MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED=true',
      'MATECLAW_TROUBLESHOOTING_GUANCE_BASE_URL=https://<verified-guance-host>',
      'MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY=<inject-from-secret-manager>',
    ].join('\n'),
  }
}
