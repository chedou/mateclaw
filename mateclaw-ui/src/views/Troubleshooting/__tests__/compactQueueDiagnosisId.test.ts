import { describe, expect, it } from 'vitest'
import diagnosisQueuePanelSource from '../DiagnosisQueuePanel.vue?raw'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'

describe('the compact troubleshooting queue', () => {
  it('shows the diagnosis id inside every clickable queue item', () => {
    const queueItem = diagnosisQueuePanelSource.match(
      /<button\s+v-for="row in rows"[\s\S]*?<\/button>/,
    )?.[0]

    expect(queueItem).toBeDefined()
    expect(queueItem).toContain('{{ row.diagnosisId }}')
  })

  it('is mounted by the formal workbench', () => {
    expect(formalWorkbenchSource)
      .toContain("import DiagnosisQueuePanel from './DiagnosisQueuePanel.vue'")
    expect(formalWorkbenchSource).toMatch(/<DiagnosisQueuePanel[\s>]/)
  })
})
