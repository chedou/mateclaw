import { describe, expect, it } from 'vitest'
import evidenceCatalogSource from '../EvidenceQueryCatalog.vue?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import guanceOnboardingSource from '../GuanceOnboardingDialog.vue?raw'
import guanceValidationSource from '../GuanceValidationDialog.vue?raw'
import investigationTraceSource from '../InvestigationTracePanel.vue?raw'
import capabilityMenuSource from '../workbenchCapabilityMenu.ts?raw'
import developerEvidenceSource from '../DeveloperEvidencePanel.vue?raw'

describe('troubleshooting operator copy uses plain language', () => {
  it('calls evidence contracts query rules on operator-facing surfaces', () => {
    const sources = [
      evidenceCatalogSource,
      guanceOnboardingSource,
      guanceValidationSource,
      capabilityMenuSource,
    ]

    for (const source of sources) expect(source).not.toContain('查询合同')
    expect(capabilityMenuSource).toContain("label: '查询规则'")
    expect(evidenceCatalogSource).toContain('系统用它确定去哪里查询、需要哪些参数、返回哪些数据')
  })

  it('explains investigation data and validation failures without contract jargon', () => {
    expect(investigationTraceSource).toContain('本次固定要查的数据（取证要求）')
    expect(investigationTraceSource).not.toContain('证据合同')
    expect(formalWorkbenchSource).toContain('返回数据格式校验未通过')
    expect(formalWorkbenchSource).not.toContain('规范化合同阻断')
  })

  it('presents Guance verification as data-source validation instead of a standalone capability', () => {
    expect(capabilityMenuSource).toContain("label: '数据源联调'")
    expect(capabilityMenuSource).not.toContain('TROUBLESHOOTING_UI_LABELS.guanceOnboarding')
    expect(evidenceCatalogSource).toContain('执行观测云只读联调')
    expect(developerEvidenceSource).toContain('前往数据源联调')
    expect(guanceOnboardingSource).not.toContain('<code>{{ stage.code }}</code>')
    expect(guanceValidationSource).not.toContain('Evidence Spine')
  })

  it('explains the bounded admin trial and keeps raw evidence out of the catalog', () => {
    expect(evidenceCatalogSource).toContain('管理员只读试跑')
    expect(evidenceCatalogSource).toContain('不会创建排障单，也不代表 T7/T8 已验收')
    expect(evidenceCatalogSource).toContain('最近只读试跑')
    expect(evidenceCatalogSource).toContain('v-if="trialError"')
    expect(evidenceCatalogSource).toContain('本次试跑未完成')
    expect(evidenceCatalogSource).not.toContain('查看原始日志')
  })

  it('lets operators validate a full 24-hour evidence window and distinguishes failures', () => {
    expect(evidenceCatalogSource).toContain('最近 24 小时')
    expect(evidenceCatalogSource).toContain("trialResult.status === 'FAILED'")
    expect(evidenceCatalogSource).toContain('数据源查询失败')
    expect(evidenceCatalogSource).toContain('查询成功，但这个时间范围没有完整证据')
  })

  it('makes system assets inspectable and versioned changes understandable', () => {
    expect(evidenceCatalogSource).toContain('查看详情')
    expect(evidenceCatalogSource).toContain('修改配置')
    expect(evidenceCatalogSource).toContain('修改会保存为新版本')
    expect(evidenceCatalogSource).toContain('不会覆盖原来的生产审计记录')
  })

  it('explains what each evidence-catalog area is for before configuration', () => {
    expect(evidenceCatalogSource).toContain('这个目录怎么用')
    expect(evidenceCatalogSource).toContain('按顺序完成系统登记、查询核对和真实联调')
  })
})
