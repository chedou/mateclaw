import { describe, expect, it } from 'vitest'
import recommendedTemplate from '@/assets/troubleshooting/t7-owner-contract-intake.recommended.template.json'
import {
  applyFirstBatchDeveloperDrafts,
  cloneRecommendedWorksheet,
  nextIncompleteOwnerSelector,
  ownerContractBatchProgress,
  ownerContractCompleteness,
  ownerRemainingFields,
  ownerContractSectionProgress,
  validateOwnerInput,
  type OwnerContract,
  type OwnerContractDocument,
} from '../t7OwnerContractIntake'

describe('t7OwnerContractIntake validation', () => {
  const template = recommendedTemplate as OwnerContractDocument

  it('ships the exact authoritative 20-row worksheet without invented owner facts', () => {
    const selected = template.contracts.filter(row => row.selectedForWindow)
    expect(selected).toHaveLength(20)
    expect(selected.reduce<Record<string, number>>((counts, row) => {
      counts[row.preparationTier] = (counts[row.preparationTier] || 0) + 1
      return counts
    }, {})).toEqual({ A_HINTED: 15, B_CONTEXT_ONLY: 2, C_SOURCE_GAPS: 3 })
    expect(selected.every(row => !ownerContractCompleteness(row.ownerContract).complete)).toBe(true)
    expect(selected.every(row => row.ownerContract?.historicalSourceReference.includes('<replace:'))).toBe(true)

    const result = validateOwnerInput(template, template, new Date('2026-08-12T00:00:00Z'))
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.issues.some(issue => issue.includes('unresolved placeholder'))).toBe(true)
    }
  })

  it('still rejects unresolved placeholders if a row is emptied', () => {
    const worksheet = cloneRecommendedWorksheet(template)
    const row = worksheet.contracts.find(item => item.selectedForWindow)!
    row.ownerContract = {
      ...(row.ownerContract as OwnerContract),
      ownerTeam: '<replace:owner-team>',
    }
    const result = validateOwnerInput(worksheet, template, new Date('2026-08-12T00:00:00Z'))
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.issues.some(issue => issue.includes('unresolved placeholder'))).toBe(true)
    }
  })

  it('builds unique developer drafts without pilot binding names', () => {
    const worksheet = cloneRecommendedWorksheet(template)
    for (const row of worksheet.contracts) {
      if (row.selectedForWindow) row.ownerContract = null
    }
    // restore empty placeholders then draft
    for (const row of worksheet.contracts) {
      if (!row.selectedForWindow) continue
      row.ownerContract = {
        ownerTeam: '<replace:owner-team>',
        ownerLevel: '<P0|P1|P2>',
        ownerScenario: '<replace:owner-verified-scenario>',
        verifiedRuntimeService: '<replace:runtime-service>',
        candidateReference: '<replace:candidate-reference>',
        serverQueryContractReference: '<replace:query-contract-reference>',
        safeSearchTerm: '<replace:safe-search-term>',
        window: '<replace:bounded-window>',
        anomalyCriterionReference: '<replace:criterion-reference>',
        diagnosisRuleReference: '<replace:rule-reference>',
        bindingRefs: {
          log_search: '<replace:log-search-binding>',
          log_trace_bundle: '<replace:trace-binding>',
          contrast_sample: '<replace:contrast-binding>',
        },
        historicalOccurredAt: '<replace:UTC-whole-seconds>',
        historicalSourceReference: '<replace:historical-source-reference>',
      }
    }
    expect(applyFirstBatchDeveloperDrafts(worksheet)).toBe(20)
    const selected = worksheet.contracts.filter(row => row.selectedForWindow)
    expect(new Set(selected.map(row => row.ownerContract!.candidateReference)).size).toBe(20)
    expect(selected.every(row => !row.ownerContract!.bindingRefs.log_search.includes('message-send'))).toBe(true)
    const hinted = selected.find(row => row.selectorKey === 'csdp:101010')!
    expect(ownerRemainingFields(hinted.ownerContract)).toEqual([
      'historicalOccurredAt',
      'historicalSourceReference',
    ])
    const sourceGap = selected.find(row => row.preparationTier === 'C_SOURCE_GAPS')!
    expect(ownerRemainingFields(sourceGap.ownerContract)).toContain('ownerLevel')
    expect(ownerRemainingFields(sourceGap.ownerContract)).toContain('safeSearchTerm')
  })

  it('turns the 15 owner facts into three plain-language completion steps', () => {
    const worksheet = cloneRecommendedWorksheet(template)
    applyFirstBatchDeveloperDrafts(worksheet)
    const contract = worksheet.contracts.find(row => row.selectorKey === 'csdp:101010')!
      .ownerContract!

    expect(ownerContractSectionProgress(contract)).toEqual([
      {
        key: 'INCIDENT',
        label: '确认这是什么故障',
        filled: 4,
        total: 6,
        complete: false,
      },
      {
        key: 'QUERY',
        label: '确认在观测云怎么查',
        filled: 6,
        total: 6,
        complete: true,
      },
      {
        key: 'DECISION',
        label: '确认平台怎么判断',
        filled: 3,
        total: 3,
        complete: true,
      },
    ])

    contract.historicalOccurredAt = '2026-08-07T09:12:00Z'
    contract.historicalSourceReference = 'alert:r-02a773'
    expect(ownerContractSectionProgress(contract)[0]).toMatchObject({
      filled: 6,
      complete: true,
    })
  })

  it('moves to the next selected incomplete row and stops when all are complete', () => {
    const worksheet = cloneRecommendedWorksheet(template)
    const selected = worksheet.contracts.filter(row => row.selectedForWindow)
    selected.forEach((row, index) => {
      row.ownerContract = completeOwnerContract(index)
    })
    selected[1].ownerContract!.historicalOccurredAt = '<replace:UTC-whole-seconds>'

    expect(nextIncompleteOwnerSelector(selected, selected[0].selectorKey))
      .toBe(selected[1].selectorKey)
    expect(nextIncompleteOwnerSelector(selected, selected[1].selectorKey))
      .toBe(selected[1].selectorKey)

    selected[1].ownerContract = completeOwnerContract(1)
    expect(nextIncompleteOwnerSelector(selected, selected[0].selectorKey)).toBeNull()
  })

  it('does not mark non-empty invalid owner facts ready or skip that row', () => {
    const worksheet = cloneRecommendedWorksheet(template)
    const selected = worksheet.contracts.filter(row => row.selectedForWindow)
    selected.forEach((row, index) => {
      row.ownerContract = completeOwnerContract(index)
    })
    selected[1].ownerContract!.ownerLevel = 'P3'
    selected[1].ownerContract!.window = '-25h'
    const asOf = new Date('2026-08-13T00:00:00Z')

    const progress = ownerContractBatchProgress(selected, asOf).get(selected[1].selectorKey)!
    expect(progress.complete).toBe(false)
    expect(progress.sections.find(section => section.key === 'INCIDENT')).toMatchObject({
      filled: 5,
      complete: false,
    })
    expect(progress.sections.find(section => section.key === 'QUERY')).toMatchObject({
      filled: 5,
      complete: false,
    })
    expect(nextIncompleteOwnerSelector(selected, selected[0].selectorKey, asOf))
      .toBe(selected[1].selectorKey)
  })

  it('keeps duplicated query semantics in the attention loop', () => {
    const worksheet = cloneRecommendedWorksheet(template)
    const selected = worksheet.contracts.filter(row => row.selectedForWindow)
    selected.forEach((row, index) => {
      row.ownerContract = completeOwnerContract(index)
    })
    const first = selected[0].ownerContract!
    const duplicate = selected[1].ownerContract!
    duplicate.verifiedRuntimeService = first.verifiedRuntimeService
    duplicate.safeSearchTerm = first.safeSearchTerm
    duplicate.window = first.window
    duplicate.bindingRefs = { ...first.bindingRefs }
    const asOf = new Date('2026-08-13T00:00:00Z')

    const progress = ownerContractBatchProgress(selected, asOf)
    expect(progress.get(selected[0].selectorKey)).toMatchObject({
      complete: false,
      issues: ['与其他条目的查询方法重复'],
    })
    expect(progress.get(selected[1].selectorKey)).toMatchObject({
      complete: false,
      issues: ['与其他条目的查询方法重复'],
    })
    expect(nextIncompleteOwnerSelector(selected, selected[0].selectorKey, asOf))
      .toBe(selected[1].selectorKey)
  })
})

function completeOwnerContract(index: number): OwnerContract {
  return {
    ownerTeam: 'CSDP',
    ownerLevel: 'P1',
    ownerScenario: `真实故障 ${index}`,
    verifiedRuntimeService: `csdp-service-${index}`,
    candidateReference: `cand:t7:${index}`,
    serverQueryContractReference: `query:t7:${index}`,
    safeSearchTerm: `error-${index}`,
    window: '-6h',
    anomalyCriterionReference: `criterion:t7:${index}`,
    diagnosisRuleReference: `rule:t7:${index}`,
    bindingRefs: {
      log_search: `binding-${index}-log`,
      log_trace_bundle: `binding-${index}-trace`,
      contrast_sample: `binding-${index}-contrast`,
    },
    historicalOccurredAt: '2026-08-07T09:12:00Z',
    historicalSourceReference: `alert:r-${index}`,
  }
}
