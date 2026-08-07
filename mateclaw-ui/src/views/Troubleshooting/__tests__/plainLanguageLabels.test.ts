import { describe, expect, it } from 'vitest'
import observabilityAssetsSource from '../ObservabilityAssetsWorkspace.vue?raw'
import evidenceCatalogHelperSource from '../evidenceCatalog.ts?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import guanceOnboardingSource from '../GuanceOnboardingDialog.vue?raw'
import guanceValidationSource from '../GuanceValidationDialog.vue?raw'
import investigationTraceSource from '../InvestigationTracePanel.vue?raw'
import capabilityMenuSource from '../workbenchCapabilityMenu.ts?raw'
import developerEvidenceSource from '../DeveloperEvidencePanel.vue?raw'

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
  })

  it('explains investigation data and validation failures without contract jargon', () => {
    expect(investigationTraceSource).toContain('本次固定要查的数据（取证要求）')
    expect(investigationTraceSource).not.toContain('证据合同')
    expect(formalWorkbenchSource).toContain('返回数据格式校验未通过')
    expect(formalWorkbenchSource).not.toContain('规范化合同阻断')
  })

  it('presents Guance verification as data-source validation instead of a standalone capability', () => {
    expect(observabilityAssetsSource).toContain('数据源联调')
    expect(capabilityMenuSource).not.toContain('TROUBLESHOOTING_UI_LABELS.guanceOnboarding')
    expect(developerEvidenceSource).toContain('前往数据源联调')
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
    expect(observabilityAssetsSource).toContain('查看详情')
    expect(observabilityAssetsSource).toContain('修改模块配置')
    expect(observabilityAssetsSource).toContain('修改会保存为新版本')
    expect(observabilityAssetsSource).toContain('不会覆盖原来的生产审计记录')
  })

  it('explains setup as system and module tools on one page', () => {
    expect(observabilityAssetsSource).toContain('可用取证工具')
    expect(observabilityAssetsSource).toContain('按系统与系统模块配置取证')
    expect(evidenceCatalogHelperSource).toContain('填写工具所需资源参数')
    expect(capabilityMenuSource).toContain("label: TROUBLESHOOTING_UI_LABELS.observabilityAssets")
    expect(capabilityMenuSource).not.toContain("command: 'evidence-catalog'")

    // 规格（要传什么 / 返回什么 / 预算）原来是另一页的全部内容，现在折在
    // 每条工具的详情里。钉住它确实在这一页，否则合并会退化成「删了一页」。
    expect(observabilityAssetsSource).toContain('规则明细：要传什么 · 返回什么 · 预算')
    expect(observabilityAssetsSource).toContain('需要传什么')
    expect(observabilityAssetsSource).toContain('规范返回')
    expect(observabilityAssetsSource).toContain('服务端固定条件')
  })
})
