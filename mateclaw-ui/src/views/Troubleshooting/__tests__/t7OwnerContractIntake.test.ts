import { describe, expect, it } from 'vitest'
import recommendedTemplate from '@/assets/troubleshooting/t7-owner-contract-intake.recommended.template.json'
import {
  applyFirstBatchDeveloperDrafts,
  cloneRecommendedWorksheet,
  ownerContractCompleteness,
  ownerRemainingFields,
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
})
