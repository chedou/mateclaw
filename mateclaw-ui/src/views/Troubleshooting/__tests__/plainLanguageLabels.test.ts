import { describe, expect, it } from 'vitest'
import evidenceCatalogSource from '../EvidenceQueryCatalog.vue?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'
import guanceOnboardingSource from '../GuanceOnboardingDialog.vue?raw'
import guanceValidationSource from '../GuanceValidationDialog.vue?raw'
import investigationTraceSource from '../InvestigationTracePanel.vue?raw'
import capabilityMenuSource from '../workbenchCapabilityMenu.ts?raw'

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
})
