import type {
  EvidenceCatalogModule,
  EvidenceCatalogSource,
  EvidenceQueryCatalog,
  EvidenceQueryContract,
  EvidenceRouteOrigin,
  ObservabilityAsset,
  ObservabilityAssetContractOption,
  SopSummary,
} from '@/api'

// 这里原先有两条常驻流程条文案（EVIDENCE_CATALOG_WORKFLOW / EVIDENCE_SETUP_WORKFLOW）。
// 两条都删了，原因不是「没人用」，而是它们**说的顺序是错的**：设置那条把「数据源联调」
// 写成第 3 步，可它是前两步的前提——你可以把系统、模块、绑定全配完，再发现根本没有
// 源可用。页面现在按 `setupGate` 说话，你站在哪一格就只说那一格的事。
//
// 目录那条随「查询规则说明书」一起退休。

export type SetupModuleEntry = {
  system: string
  service: string
  displayName: string
  asset: ObservabilityAsset | null
  module: EvidenceCatalogModule | null
}

export type ToolChecklistItem = {
  key: 'enable' | 'workspace' | 'params' | 'route' | 'source' | 'trial'
  label: string
  detail: string
  done: boolean
}

export type ModuleToolSetup = {
  signalKind: string
  contractRef: string
  scenario: string
  question: string
  summary: string
  requiredAssetParameters: string[]
  enabled: boolean
  status: 'READY' | 'BLOCKED' | 'NOT_ENABLED'
  statusLabel: string
  checklist: ToolChecklistItem[]
  contract: EvidenceQueryContract | null
}

export type ModuleOnboardingStep = {
  code: 'SOURCE' | 'ASSET' | 'TOOLS' | 'PLAYBOOK' | 'ACCEPTANCE'
  label: string
  state: 'DONE' | 'TODO' | 'UNKNOWN'
  detail: string
  action: 'source' | 'asset' | 'tools' | 'playbook' | 'acceptance'
}

export type ModuleOnboardingReadiness = {
  status: 'READY_FOR_PILOT' | 'NEEDS_CONFIGURATION'
  completedSteps: number
  totalSteps: number
  operationalPlaybooks: SopSummary[]
  steps: ModuleOnboardingStep[]
  nextStep: ModuleOnboardingStep | null
}

// 首批正式试点模块只有各自这一条规则可证明目标竖线已就绪；同服务的其他规则不能替代它。
const REQUIRED_PILOT_PLAYBOOK_ROUTE_BY_MODULE: Readonly<Record<string, string>> = {
  'csdp::csdp-task': 'csdp:scenario:cti_create_conversation_failed',
  'csdp::csdp-wechat': 'csdp:904003',
}

export function signalKindLabel(signalKind: string): string {
  return {
    log_search: '日志检索',
    log_trace_bundle: '链路还原',
    contrast_sample: '成功失败对照',
    external_api_http_failure: '外部接口 HTTP 失败',
    incident_reported_external_http_failure: '告警已报告的外部接口失败',
    incident_reported_business_policy_rejection: '告警已报告的业务规则拒绝',
    error_log_scan: '错误日志巡检',
    monitor_event_scan: '监控告警巡检',
    k8s_workload_health: 'K8s 工作负载健康',
    k8s_pod_status: '服务 Pod 状态',
    k8s_node_status: '服务 Node 状态',
    host_status: '服务主机状态',
    log_count: '日志计数',
    synthetic_probe: '拨测',
    k8s_health: 'K8s 健康',
    monitor_checker: '监控拨测',
    rum_error: '前端错误',
    metric_query: '指标查询',
  }[signalKind] || signalKind
}

function preferModuleAsset(
  assets: ReadonlyArray<ObservabilityAsset>,
  system: string,
  service: string,
): ObservabilityAsset | null {
  const matches = assets.filter(asset =>
    asset.system.trim().toLowerCase() === system.trim().toLowerCase()
      && asset.service.trim().toLowerCase() === service.trim().toLowerCase())
  return matches.find(asset => asset.origin === 'WORKSPACE')
    || matches[0]
    || null
}

/** 合并目录模块与已有资产，供取证接入左侧导航。 */
export function listSetupModules(
  catalog: EvidenceQueryCatalog | null,
  assets: ReadonlyArray<ObservabilityAsset> | null | undefined,
): SetupModuleEntry[] {
  const assetList = assets || []
  const seen = new Set<string>()
  const entries: SetupModuleEntry[] = []

  for (const system of catalog?.systems || []) {
    for (const module of system.modules) {
      const key = `${system.system.trim().toLowerCase()}::${module.service.trim().toLowerCase()}`
      seen.add(key)
      const asset = preferModuleAsset(assetList, system.system, module.service)
      entries.push({
        system: system.system,
        service: module.service,
        displayName: asset?.displayName || module.service,
        asset,
        module,
      })
    }
  }

  for (const asset of assetList) {
    const key = `${asset.system.trim().toLowerCase()}::${asset.service.trim().toLowerCase()}`
    if (seen.has(key)) continue
    seen.add(key)
    entries.push({
      system: asset.system,
      service: asset.service,
      displayName: asset.displayName || asset.service,
      asset: preferModuleAsset(assetList, asset.system, asset.service),
      module: null,
    })
  }

  return entries.sort((left, right) => {
    const bySystem = left.system.localeCompare(right.system)
    return bySystem || left.service.localeCompare(right.service)
  })
}

function assetHasParameter(
  asset: ObservabilityAsset | null | undefined,
  parameter: string,
): boolean {
  if (!asset) return false
  if (parameter === 'environment') return Boolean(asset.environment?.trim())
  if (parameter === 'region') return Boolean(asset.region?.trim())
  if (parameter === 'cluster') return Boolean(asset.cluster?.trim())
  if (parameter === 'namespace') return Boolean(asset.namespace?.trim())
  return Boolean(asset.parameters?.[parameter]?.trim())
}

/** 针对单个系统模块，列出可用工具及每项还缺什么。 */
export function buildModuleToolSetups(input: {
  options: ReadonlyArray<ObservabilityAssetContractOption>
  module: EvidenceCatalogModule | null | undefined
  asset: ObservabilityAsset | null | undefined
  sourceReady: boolean
}): ModuleToolSetup[] {
  const options = mergeObservabilityAssetContractOptions(
    input.options,
    input.module?.contracts || [],
  )
  const asset = input.asset || null
  const boundRefs = new Set(Object.values(asset?.signalBindings || {}))
  const contractsByRef = new Map(
    (input.module?.contracts || []).map(contract => [contract.contractRef, contract]),
  )

  return options
    .map((option): ModuleToolSetup => {
      const contract = contractsByRef.get(option.contractRef) || null
      const enabled = boundRefs.has(option.contractRef)
        || Boolean(asset?.signalBindings?.[option.signalKind] === option.contractRef)
      const missingParams = option.requiredAssetParameters.filter(
        parameter => !assetHasParameter(asset, parameter),
      )
      const workspaceReady = Boolean(asset && asset.origin === 'WORKSPACE' && asset.enabled)
      const routeReady = Boolean(
        contract
          && contract.route.origin !== 'UNCONFIGURED'
          && !contract.route.explicitlyDisabled
          && contract.route.platforms.length,
      )
      const trialBlocker = contract
        ? directTrialBlockReason(contract, asset)
        : (enabled ? '查询规则尚未投影到目录，请刷新或先完成绑定' : '先启用并绑定这条工具')
      const checklist: ToolChecklistItem[] = [
        {
          key: 'enable',
          label: '启用并选择取证方法',
          detail: enabled
            ? `已绑定 ${option.contractRef}`
            : '在模块资产里勾选这条已审核规则',
          done: enabled,
        },
        {
          key: 'workspace',
          label: '登记 Workspace 模块资产',
          detail: workspaceReady
            ? `${asset!.environment || '已声明环境'} · v${asset!.version}`
            : asset?.origin === 'DEPLOYMENT'
              ? '当前仍是部署默认，需接管为 Workspace 资产'
              : '登记系统、模块、环境后才能试跑',
          done: workspaceReady,
        },
        {
          key: 'params',
          label: '填写工具所需资源参数',
          detail: option.requiredAssetParameters.length
            ? (missingParams.length
              ? `还缺：${missingParams.map(assetParameterLabel).join('、')}`
              : option.requiredAssetParameters.map(assetParameterLabel).join('、'))
            : '这条工具不额外要求资产参数',
          done: missingParams.length === 0,
        },
        {
          key: 'route',
          label: '声明取证路由',
          detail: contract
            ? (routeReady
              ? `${routeOriginLabel(contract.route.origin)} · ${contract.route.platforms.join(' → ')}`
              : '系统 × 信号种类尚未声明可用平台')
            : '绑定并投影到目录后可改路由',
          done: routeReady,
        },
        {
          key: 'source',
          label: '观测云数据源就绪',
          detail: input.sourceReady
            ? '端点与凭据已配置'
            : '先检查数据连接是否可用',
          done: input.sourceReady,
        },
        {
          key: 'trial',
          label: '具备管理员只读试跑条件',
          detail: trialBlocker || '可以对这条工具做只读试跑',
          done: !trialBlocker && Boolean(contract?.runnable),
        },
      ]

      let status: ModuleToolSetup['status'] = 'NOT_ENABLED'
      let statusLabel = '未启用'
      if (enabled && checklist.every(item => item.done)) {
        status = 'READY'
        statusLabel = '可运行'
      } else if (enabled) {
        status = 'BLOCKED'
        statusLabel = '已启用 · 待补齐'
      }

      return {
        signalKind: option.signalKind,
        contractRef: option.contractRef,
        scenario: option.scenario,
        question: option.question,
        summary: option.summary,
        requiredAssetParameters: option.requiredAssetParameters,
        enabled,
        status,
        statusLabel,
        checklist,
        contract,
      }
    })
    .sort((left, right) => {
      const rank = { READY: 0, BLOCKED: 1, NOT_ENABLED: 2 }
      const byStatus = rank[left.status] - rank[right.status]
      return byStatus || left.signalKind.localeCompare(right.signalKind)
        || left.contractRef.localeCompare(right.contractRef)
    })
}

/**
 * 把一个模块的分散配置折成一张可复核的五步接入清单。
 *
 * 这里只使用已有服务端投影，不根据名称猜测系统、模块或 Playbook。
 * 排障方案列表因权限不可读时保留 UNKNOWN，不能伪装成“未配置”。
 */
export function buildModuleOnboardingReadiness(input: {
  entry: SetupModuleEntry
  tools: ReadonlyArray<ModuleToolSetup>
  sources: ReadonlyArray<EvidenceCatalogSource>
  playbooks: ReadonlyArray<SopSummary> | null
}): ModuleOnboardingReadiness {
  const { entry } = input
  const requiredPlaybookRoute = requiredPlaybookRouteForModule(entry.system, entry.service)
  const operationalPlaybooks = input.playbooks === null
    ? []
    : input.playbooks.filter(playbook => playbook.operational
      && sameIdentity(playbook.system, entry.system)
      && sameIdentity(playbook.service, entry.service)
      && (!requiredPlaybookRoute || sameIdentity(playbook.routeKey, requiredPlaybookRoute)))
  const enabledTools = input.tools.filter(tool => tool.enabled)
  const readyRealPlatforms = new Set(input.sources
    .filter(source => source.status === 'READY' && !isRecordedReplaySource(source.platform))
    .map(source => normalizedIdentity(source.platform)))
  const toolRealPlatforms = enabledTools.map(tool => (tool.contract?.route.platforms || [])
    .filter(platform => !isRecordedReplaySource(platform))
    .filter(platform => readyRealPlatforms.has(normalizedIdentity(platform))))
  const assetPlatformReady = Boolean(entry.asset?.platform
    && readyRealPlatforms.has(normalizedIdentity(entry.asset.platform)))
  const moduleRealSourceReady = enabledTools.length
    ? toolRealPlatforms.every(platforms => platforms.length > 0)
    : assetPlatformReady
  const routedRealPlatforms = [...new Set(toolRealPlatforms.flat())]
  const source: ModuleOnboardingStep = {
    code: 'SOURCE',
    label: '真实数据源可用',
    state: moduleRealSourceReady ? 'DONE' : 'TODO',
    detail: moduleRealSourceReady
      ? `${routedRealPlatforms.length
        ? routedRealPlatforms.join('、')
        : entry.asset?.platform || '真实数据源'} 已就绪并被当前模块引用`
      : enabledTools.length
        ? '当前模块的已启用方法还没有路由到可用的真实数据源'
        : '请先检查当前模块的数据源连接和取证路由',
    action: 'source',
  }
  const workspaceAssetReady = Boolean(
    entry.asset?.origin === 'WORKSPACE' && entry.asset.enabled,
  )
  const asset: ModuleOnboardingStep = {
    code: 'ASSET',
    label: '系统模块已登记',
    state: workspaceAssetReady ? 'DONE' : 'TODO',
    detail: workspaceAssetReady
      ? `${entry.asset!.environment || '已登记环境'} · Workspace v${entry.asset!.version}`
      : entry.asset?.origin === 'DEPLOYMENT'
        ? '当前仍使用部署默认，请接管为 Workspace 资产'
        : '请登记系统、模块、环境和资源范围',
    action: 'asset',
  }
  const toolsReady = enabledTools.length > 0
    && enabledTools.every(tool => tool.status === 'READY')
  const tools: ModuleOnboardingStep = {
    code: 'TOOLS',
    label: '取证方法可运行',
    state: toolsReady ? 'DONE' : 'TODO',
    detail: toolsReady
      ? `${enabledTools.length} 条已绑定方法具备只读试跑条件`
      : enabledTools.length
        ? `${enabledTools.filter(tool => tool.status === 'READY').length}/${enabledTools.length} 条已绑定方法可运行`
        : '还没有为该模块绑定已审核的取证方法',
    action: 'tools',
  }
  const playbook: ModuleOnboardingStep = input.playbooks === null
    ? {
        code: 'PLAYBOOK',
        label: '排障方案已生效',
        state: 'UNKNOWN',
        detail: '当前账号未读取到排障方案状态',
        action: 'playbook',
      }
    : {
        code: 'PLAYBOOK',
        label: '排障方案已生效',
        state: operationalPlaybooks.length ? 'DONE' : 'TODO',
        detail: operationalPlaybooks.length
          ? `${operationalPlaybooks.length} 条已审核排障方案可命中该模块`
          : '还没有该模块可命中的已审核排障方案',
        action: 'playbook',
      }
  const accepted = entry.module?.acceptance.status === 'ACCEPTED'
  const acceptance: ModuleOnboardingStep = {
    code: 'ACCEPTANCE',
    label: '负责人已确认查询口径',
    state: accepted ? 'DONE' : 'TODO',
    detail: accepted
      ? `已由 ${entry.module?.acceptance.acceptedBy || '负责人'} 确认`
      : entry.module?.acceptance.status === 'STALE'
        ? '配置已变更，原验收失效，需重新确认'
        : '投产前需由 Workspace 负责人确认字段、索引、时间窗和关联口径',
    action: 'acceptance',
  }
  const steps = [source, asset, tools, playbook, acceptance]
  const completedSteps = steps.filter(step => step.state === 'DONE').length
  const nextStep = steps.find(step => step.state !== 'DONE') || null
  return {
    status: completedSteps === steps.length
      ? 'READY_FOR_PILOT'
      : 'NEEDS_CONFIGURATION',
    completedSteps,
    totalSteps: steps.length,
    operationalPlaybooks,
    steps,
    nextStep,
  }
}

function isRecordedReplaySource(platform: string) {
  return normalizedIdentity(platform).startsWith('recorded-replay')
}

function requiredPlaybookRouteForModule(system: string, service: string) {
  return REQUIRED_PILOT_PLAYBOOK_ROUTE_BY_MODULE[
    `${normalizedIdentity(system)}::${normalizedIdentity(service)}`
  ] || null
}

function normalizedIdentity(value: string | null | undefined) {
  return (value || '').trim().toLowerCase()
}

function sameIdentity(left: string | null | undefined, right: string | null | undefined) {
  return normalizedIdentity(left) === normalizedIdentity(right)
}

export type ModuleNextAction = {
  code: 'READY' | 'CONFIGURE_ASSET' | 'FIX_BLOCKERS' | 'ACCEPTANCE' | 'SOURCE_DOWN'
  title: string
  detail: string
  primaryCta: 'asset' | 'acceptance' | 'none'
}

export function findModuleAsset(
  assets: ReadonlyArray<ObservabilityAsset> | null | undefined,
  system: string,
  service: string,
): ObservabilityAsset | null {
  const systemKey = system.trim().toLowerCase()
  const serviceKey = service.trim().toLowerCase()
  return (assets || []).find(asset =>
    asset.system.trim().toLowerCase() === systemKey
      && asset.service.trim().toLowerCase() === serviceKey) || null
}

export function moduleNextAction(
  module: EvidenceCatalogModule,
  asset: ObservabilityAsset | null | undefined,
  sourceReady: boolean,
): ModuleNextAction {
  if (!sourceReady) {
    return {
      code: 'SOURCE_DOWN',
      title: '数据源尚未就绪',
      detail: '先确认观测云端点与凭据已配置，再回来看这个模块。',
      primaryCta: 'acceptance',
    }
  }
  if (!asset || asset.origin !== 'WORKSPACE') {
    return {
      code: 'CONFIGURE_ASSET',
      title: '先登记 Workspace 模块配置',
      detail: asset?.origin === 'DEPLOYMENT'
        ? '当前仍是部署默认回落；管理员试跑与验收需要先接管为 Workspace 配置。'
        : '还没有这个模块的配置。登记环境与查询规则绑定后，才能试跑。',
      primaryCta: 'asset',
    }
  }
  if (!asset.enabled) {
    return {
      code: 'CONFIGURE_ASSET',
      title: '资产已停用',
      detail: '新建一个启用版本后，才能继续试跑或联调。',
      primaryCta: 'asset',
    }
  }
  if (module.runnableContracts < module.contracts.length || module.blockers.length) {
    return {
      code: 'FIX_BLOCKERS',
      title: '还有查询规则受阻',
      detail: module.blockers[0]
        || '点开受阻规则查看阻断点；常见原因是路由未声明或绑定未就绪。',
      primaryCta: 'none',
    }
  }
  if (module.acceptance.status !== 'ACCEPTED') {
    return {
      code: 'ACCEPTANCE',
      title: '规则可跑，等待真源验收',
      detail: '可以先做管理员只读试跑；投产前再执行观测云联调与 owner 验收。',
      primaryCta: 'acceptance',
    }
  }
  return {
    code: 'READY',
    title: '这个模块已可取证',
    detail: '日常排障直接用；规则或资源变更后再回来核对。',
    primaryCta: 'none',
  }
}

export function mergeObservabilityAssetContractOptions(
  assetOptions: ReadonlyArray<ObservabilityAssetContractOption>,
  queryContracts: ReadonlyArray<EvidenceQueryContract>,
): ObservabilityAssetContractOption[] {
  if (assetOptions.length > 0) {
    return [...assetOptions].sort((left, right) =>
      left.signalKind.localeCompare(right.signalKind)
        || left.contractRef.localeCompare(right.contractRef))
  }

  const options = new Map<string, ObservabilityAssetContractOption>()
  for (const contract of queryContracts) {
    options.set(contract.contractRef, {
      contractRef: contract.contractRef,
      signalKind: contract.signalKind,
      scenario: contract.scenario,
      question: contract.question,
      summary: contract.summary,
      requiredAssetParameters: contract.parameters
        .filter(parameter => parameter.source === 'SYSTEM_ASSET')
        .map(parameter => parameter.name)
        .sort(),
    })
  }
  return [...options.values()].sort((left, right) =>
    left.signalKind.localeCompare(right.signalKind)
      || left.contractRef.localeCompare(right.contractRef))
}

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
    SYSTEM_ASSET: '系统模块取证配置',
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
    probe_name: '拨测任务名（probe_name，须为 ASCII）',
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
  const parameterOrder = ['namespace', 'cluster', 'region', 'deployment', 'probe_name', 'monitor_checker']
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
    return '请先在“接入系统”中登记这个模块，再执行管理员试跑'
  }
  if (asset !== undefined && !asset?.enabled) {
    return 'Workspace 模块取证配置已停用，请先新建启用版本'
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
    return '资源范围必须先在“接入系统”中登记，不能在试跑时临时填写'
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
