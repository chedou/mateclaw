import type {
  EvidenceQueryCatalog,
  EvidenceQueryContract,
  EvidenceRouteOrigin,
  ObservabilityAsset,
} from '@/api'

export function catalogSummary(catalog: EvidenceQueryCatalog | null) {
  const systems = catalog?.systems.length ?? 0
  const modules = catalog?.systems.reduce(
    (total, system) => total + system.modules.length, 0,
  ) ?? 0
  const contracts = catalog?.systems.reduce(
    (total, system) => total + system.modules.reduce(
      (moduleTotal, module) => moduleTotal + module.contracts.length, 0,
    ), 0,
  ) ?? 0
  const runnable = catalog?.systems.reduce(
    (total, system) => total + system.modules.reduce(
      (moduleTotal, module) => moduleTotal + module.runnableContracts, 0,
    ), 0,
  ) ?? 0
  return { systems, modules, contracts, runnable }
}

export function contractMatches(contract: EvidenceQueryContract, keyword: string): boolean {
  const normalized = keyword.trim().toLowerCase()
  if (!normalized) return true
  return [
    contract.contractRef,
    contract.signalKind,
    contract.scenario,
    contract.question,
    contract.summary,
    contract.adapter,
    contract.namespace,
    ...contract.fixedConditions,
    ...contract.canonicalOutputs,
  ].some(value => value.toLowerCase().includes(normalized))
}

export function routeOriginLabel(origin: EvidenceRouteOrigin): string {
  return {
    WORKSPACE: 'Workspace 声明',
    DEPLOYMENT: '部署默认',
    UNCONFIGURED: '未配置',
  }[origin]
}

export function bindingStatusLabel(status: string): string {
  return {
    NOT_ROUTED: '未路由',
    UNAUTHORIZED: '未授权绑定',
    INVALID_BINDING: '绑定无效',
    READY_FOR_VALIDATION: '可联调',
    CANONICAL_RESULT_OBSERVED: '已观测到规范证据',
  }[status] ?? status
}

export function parameterSourceLabel(source: string): string {
  return {
    INCIDENT_OR_CURRENT_TIME: '故障时间 / 当前时间',
    EVIDENCE_REQUEST: '证据请求',
    PREVIOUS_EVIDENCE: '前一步证据',
    INCIDENT: '排障事件',
    SYSTEM_ASSET: '系统观测资产',
    EVIDENCE_REQUEST_TARGET: '排查指南的证据目标',
  }[source] ?? source
}

export function acceptanceStatusLabel(status: string): string {
  return {
    ACCEPTED: '已验收',
    NOT_ACCEPTED: '待验收',
    STALE: '验收已过期',
    BLOCKED: '暂不可验收',
    UNAVAILABLE: '状态不可用',
  }[status] ?? status
}

export function runtimeStateLabel(status: string): string {
  return {
    CONFIGURED: '已配置',
    MISSING: '未配置',
    NOT_REPORTED: '该适配器未提供',
  }[status] ?? status
}

export type ObservabilityAssetDraft = {
  system: string
  service: string
  displayName: string
  environment: string
  enabled: boolean
  contractRefs: Record<string, string | undefined>
  parameterValues: Record<string, string>
  requiredAssetParameters: string[]
  reason: string
}

export function assetParameterLabel(parameter: string): string {
  return {
    monitor_checker: '观测云监控规则标识（monitor_checker）',
    deployment: 'Kubernetes Deployment',
    namespace: 'Kubernetes Namespace',
    cluster: '集群标识',
    region: '区域标识',
    environment: '环境',
  }[parameter] || parameter
}

export function observabilityAssetDraftReadiness(
  draft: ObservabilityAssetDraft,
): { ready: boolean, missing: string[] } {
  const missing: string[] = []
  if (!draft.system.trim()) missing.push('系统标识')
  if (!draft.service.trim()) missing.push('模块 / 服务标识')
  if (!draft.displayName.trim()) missing.push('显示名称')
  if (!draft.environment.trim()) missing.push('环境')
  const bindings = Object.values(draft.contractRefs).filter(
    (value): value is string => typeof value === 'string' && Boolean(value.trim()),
  )
  if (draft.enabled && !bindings.length) missing.push('至少一条已审核查询规则')
  const parameterOrder = ['namespace', 'cluster', 'region', 'deployment', 'monitor_checker']
  const requiredParameters = [...draft.requiredAssetParameters].sort((left, right) => {
    const leftIndex = parameterOrder.indexOf(left)
    const rightIndex = parameterOrder.indexOf(right)
    if (leftIndex === -1 && rightIndex === -1) return left.localeCompare(right)
    if (leftIndex === -1) return 1
    if (rightIndex === -1) return -1
    return leftIndex - rightIndex
  })
  for (const parameter of requiredParameters) {
    if (!draft.parameterValues[parameter]?.trim()) {
      missing.push(assetParameterLabel(parameter))
    }
  }
  if (!draft.reason.trim()) missing.push('变更原因')
  return { ready: missing.length === 0, missing }
}

export function directTrialBlockReason(
  contract: EvidenceQueryContract | null,
  asset?: ObservabilityAsset | null,
): string {
  if (!contract) return '请先选择一条查询规则'
  if (asset !== undefined && (!asset || asset.origin !== 'WORKSPACE')) {
    return '请先到系统观测资产接管为 Workspace 系统观测资产，再执行管理员试跑'
  }
  if (asset !== undefined && !asset?.enabled) {
    return 'Workspace 系统观测资产已停用，请先新建启用版本'
  }
  if (!contract.runnable) return '查询规则当前不可运行，请先处理阻断点'
  if (contract.adapter.toLowerCase() !== 'guance') {
    return '当前只开放观测云只读适配器试跑'
  }
  if (contract.parameters.some(parameter =>
    parameter.required && parameter.source === 'PREVIOUS_EVIDENCE')) {
    return '这一步依赖前一步证据，请从排障详情运行完整证据链'
  }
  const resourceParameters = new Set([
    'monitor_checker', 'deployment', 'namespace', 'cluster', 'region', 'environment',
  ])
  if (contract.parameters.some(parameter => parameter.required
    && parameter.source === 'EVIDENCE_REQUEST_TARGET'
    && resourceParameters.has(parameter.name))) {
    return '资源范围必须先登记到系统观测资产，不能在试跑时临时填写'
  }
  return ''
}

export function moveOrderedItem<T>(items: T[], index: number, offset: -1 | 1): T[] {
  const target = index + offset
  if (index < 0 || index >= items.length || target < 0 || target >= items.length) {
    return [...items]
  }
  const moved = [...items]
  const current = moved[index]
  moved[index] = moved[target]
  moved[target] = current
  return moved
}
