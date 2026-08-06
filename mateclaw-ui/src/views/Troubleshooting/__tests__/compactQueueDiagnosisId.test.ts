import { describe, expect, it } from 'vitest'
import formalWorkbenchSource from '../FormalWorkbench.vue?raw'

describe('the compact troubleshooting queue', () => {
  it('shows the diagnosis id inside every clickable queue item', () => {
    const queueItem = formalWorkbenchSource.match(
      /<button\s+v-for="row in rows"[\s\S]*?<\/button>/,
    )?.[0]

    expect(queueItem).toBeDefined()
    expect(queueItem).toContain('{{ row.diagnosisId }}')
  })
})
