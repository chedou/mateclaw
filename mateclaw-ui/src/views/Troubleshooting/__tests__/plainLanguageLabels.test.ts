import { describe, expect, it } from 'vitest'
import observabilityAssetsSource from '../ObservabilityAssetsWorkspace.vue?raw'
import evidenceCatalogHelperSource from '../evidenceCatalog.ts?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import guanceOnboardingSource from '../GuanceOnboardingDialog.vue?raw'
import guanceValidationSource from '../GuanceValidationDialog.vue?raw'
import investigationTraceSource from '../InvestigationTracePanel.vue?raw'
import capabilityMenuSource from '../workbenchCapabilityMenu.ts?raw'
import developerEvidenceSource from '../DeveloperEvidencePanel.vue?raw'
import businessSummarySource from '../BusinessSummaryCard.vue?raw'
import synthesisPreviewSource from '../SynthesisPreviewDialog.vue?raw'
import evaluationLedgerSource from '../EvaluationSampleLedgerWorkspace.vue?raw'

describe('troubleshooting operator copy uses plain language', () => {
  it('calls evidence contracts query rules on operator-facing surfaces', () => {
    const sources = [
      observabilityAssetsSource,
      guanceOnboardingSource,
      guanceValidationSource,
      capabilityMenuSource,
    ]

    for (const source of sources) expect(source).not.toContain('查询合同')
    expect(observabilityAssetsSource).toContain('查询规则')
    expect(observabilityAssetsSource).toContain('选择这个模块的取证方法')
    expect(observabilityAssetsSource).toContain('发生故障时，系统会按照这里选择的方法查询日志、调用链、拨测或服务状态')
    expect(observabilityAssetsSource).toContain('取证时要查询哪个资源')
    expect(observabilityAssetsSource).not.toContain('绑定已审核查询规则')
  })

  it('explains investigation data and validation failures without contract jargon', () => {
    expect(investigationTraceSource).toContain('本次固定要查的数据（取证要求）')
    expect(investigationTraceSource).not.toContain('证据合同')
    expect(formalWorkbenchSource).toContain('返回数据格式校验未通过')
    expect(formalWorkbenchSource).not.toContain('规范化合同阻断')
  })

  it('distinguishes a persisted read-only evidence run from the first conclusion timer', () => {
    expect(investigationTraceSource).toContain('本次只读取证用时')
    expect(investigationTraceSource).toContain("stage.key === 'EVIDENCE_COLLECTION'")
    expect(investigationTraceSource).toContain('trace.investigationDuration')
  })

  it('leads with the four outcomes and keeps technical audit details optional', () => {
    expect(investigationTraceSource).toContain('本次排障的四个关键节点')
    expect(investigationTraceSource).toContain('>关键节点</button>')
    expect(investigationTraceSource).toContain('>完整过程</button>')
    expect(investigationTraceSource).toContain("activeView === 'overview'")
    expect(investigationTraceSource).toContain("activeView === 'steps'")
    expect(investigationTraceSource).toContain("ref<'overview' | 'steps' | 'relation'>('overview')")
    expect(investigationTraceSource).toContain('class="stage-rail overview-rail"')
    expect(investigationTraceSource).toContain('class="stage-inspector overview-inspector"')
    expect(investigationTraceSource).toContain('@click="selectedOverviewKey = item.key"')
    expect(investigationTraceSource).toContain('查看对应完整步骤')
    expect(investigationTraceSource).toContain('openOverviewStage(selectedOverview.key)')
    expect(investigationTraceSource).not.toContain('flow-overview-line')
    expect(investigationTraceSource).toContain('发生了什么')
    expect(investigationTraceSource).toContain('按什么方法查')
    expect(investigationTraceSource).toContain('查到了什么')
    expect(investigationTraceSource).toContain('最后结论')
    expect(investigationTraceSource).toContain('本次结果')
    expect(investigationTraceSource).toContain('检查点')
    expect(investigationTraceSource).toContain('下一步')
    expect(investigationTraceSource).not.toContain('系统实际做了什么')
    expect(investigationTraceSource).not.toContain('为什么继续下一步')
    expect(investigationTraceSource).toContain('查看本步技术记录')
    expect(investigationTraceSource).toContain('container-type:inline-size')
    expect(investigationTraceSource).toContain('@container (max-width:720px)')
    expect(developerEvidenceSource).toContain('展开排障过程与证据')
    expect(developerEvidenceSource).not.toContain('展开开发证据台')
  })

  it('presents PS-linked log records without pretending they are a full distributed trace', () => {
    expect(developerEvidenceSource).toContain('关联日志摘要')
    expect(developerEvidenceSource).toContain('PS ID 关联日志轨迹')
    expect(developerEvidenceSource).toContain('这里不是完整的跨服务 Trace')
    expect(developerEvidenceSource).toContain('关联日志')
    expect(developerEvidenceSource).toContain('异常日志')
    expect(developerEvidenceSource).toContain('查看全部 {{ developer.callChain.hops.length }} 条关联日志')
    expect(developerEvidenceSource).not.toContain('PS / Trace 链路')
    expect(developerEvidenceSource).not.toContain('链路节点')
    expect(developerEvidenceSource).toContain('故障请求和正常请求有什么不同')
    expect(developerEvidenceSource).toContain('查看精确数量与证据引用')
    expect(developerEvidenceSource).not.toContain('developer.contrast.failedSample')
    expect(developerEvidenceSource).not.toContain('developer.contrast.baselineSample')
    expect(developerEvidenceSource).not.toContain('class="hop-line"')
    expect(developerEvidenceSource).not.toContain('contrast-diff-container')
    expect(developerEvidenceSource).not.toContain("import('monaco-editor')")
    expect(developerEvidenceSource).toContain('container-type:inline-size')
    expect(developerEvidenceSource).toContain('@container (max-width:900px)')
  })

  it('translates evidence counts and confidence before exposing technical detail', () => {
    expect(investigationTraceSource).toContain('evidenceComparisonNarrative')
    expect(investigationTraceSource).toContain('!isEvidenceCount(failureMatches)')
    expect(investigationTraceSource).toContain('请求对比没有取得可用数据')
    expect(developerEvidenceSource).toContain('contrastNarrative.summary')
    expect(developerEvidenceSource).toContain('查看精确数量与证据引用')
    expect(guanceValidationSource).toContain('spineContrastNarrative.summary')
    expect(guanceValidationSource).not.toContain('failureMatchCount }}/{{')
    expect(synthesisPreviewSource).toContain('previewContrastNarrative.summary')
    expect(synthesisPreviewSource).not.toContain('失败样本命中特征')
    expect(evaluationLedgerSource).toContain('条关联日志')
    expect(evaluationLedgerSource).toContain('sampleContrastNarrative(sample)?.summary')
    expect(businessSummarySource).toContain('confidencePresentation.label')
    expect(businessSummarySource).not.toContain('可信等级 {{ business.confidence }}')
  })

  it('presents Guance verification as a data-connection check instead of a standalone capability', () => {
    expect(observabilityAssetsSource).toContain('检查数据连接')
    expect(observabilityAssetsSource).not.toContain('开始数据源联调')
    expect(capabilityMenuSource).not.toContain('TROUBLESHOOTING_UI_LABELS.guanceOnboarding')
    expect(developerEvidenceSource).toContain('检查数据连接')
    expect(guanceOnboardingSource).not.toContain('<code>{{ stage.code }}</code>')
    expect(guanceValidationSource).not.toContain('Evidence Spine')
  })

  it('keeps admin trials on the evidence setup workspace', () => {
    expect(observabilityAssetsSource).toContain('管理员只读试跑')
    expect(observabilityAssetsSource).toContain('不会创建排障单，也不代表真源已验收')
    expect(observabilityAssetsSource).toContain('最近只读试跑')
    expect(observabilityAssetsSource).toContain('v-if="trialError"')
    expect(observabilityAssetsSource).toContain('本次试跑未完成')
    expect(observabilityAssetsSource).not.toContain('查看原始日志')
  })

  it('lets operators validate a full 24-hour evidence window and distinguishes failures', () => {
    expect(observabilityAssetsSource).toContain('最近 24 小时')
    expect(observabilityAssetsSource).toContain("trialResult.status === 'FAILED'")
    expect(observabilityAssetsSource).toContain('数据源查询失败')
    expect(observabilityAssetsSource).toContain('查询成功，但这个时间范围没有完整证据')
  })

  it('makes module setup inspectable and versioned changes understandable', () => {
    expect(observabilityAssetsSource).toContain('>查看</el-button>')
    expect(observabilityAssetsSource).toContain('登记 / 接管')
    expect(observabilityAssetsSource).toContain('修改模块配置')
    expect(observabilityAssetsSource).toContain('修改会保存为新版本')
    expect(observabilityAssetsSource).toContain('不会覆盖原来的生产审计记录')
  })

  it('separates module, tool, and data-source setup into plain-language menus', () => {
    expect(observabilityAssetsSource).toContain('取证方法列表')
    expect(observabilityAssetsSource).toContain('数据源列表')
    expect(capabilityMenuSource).toContain("label: '接入系统'")
    expect(capabilityMenuSource).toContain("label: '取证方法'")
    expect(capabilityMenuSource).toContain("label: '数据连接'")
    expect(capabilityMenuSource).toContain("label: '高级设置'")
    expect(observabilityAssetsSource).toContain("setupSection === 'source'")
    expect(observabilityAssetsSource).toContain("setupSection === 'tools'")
    expect(observabilityAssetsSource).toContain("setupSection === 'modules'")
    expect(observabilityAssetsSource).toContain('系统模块列表')
    expect(observabilityAssetsSource).toContain('系统标识相同的模块归在同一系统下')
    expect(observabilityAssetsSource).toContain('module-list-workspace')
    expect(observabilityAssetsSource).toContain('tool-list-workspace')
    expect(observabilityAssetsSource).toContain('source-list-workspace')
    expect(observabilityAssetsSource).toContain('management-table')
    expect(observabilityAssetsSource).not.toContain('class="assets-workspace"')
    expect(observabilityAssetsSource).toContain('选择模块所属系统')
    expect(observabilityAssetsSource).toContain('就绪检查')
    expect(observabilityAssetsSource).toContain('按工具状态筛选')
    expect(observabilityAssetsSource).toContain('每行只保留当前最需要的操作')
    expect(evidenceCatalogHelperSource).toContain('填写工具所需资源参数')
    expect(capabilityMenuSource).toContain("section: 'modules'")
    expect(capabilityMenuSource).not.toContain("command: 'evidence-catalog'")

    // 规格（要传什么 / 返回什么 / 预算）原来是另一页的全部内容，现在折在
    // 每条工具的详情里。钉住它确实在这一页，否则合并会退化成「删了一页」。
    expect(observabilityAssetsSource).toContain('取证方法详情：需要什么 · 返回什么 · 执行限制')
    expect(observabilityAssetsSource).toContain('需要传什么')
    expect(observabilityAssetsSource).toContain('规范返回')
    expect(observabilityAssetsSource).toContain('服务端固定条件')
  })
})
