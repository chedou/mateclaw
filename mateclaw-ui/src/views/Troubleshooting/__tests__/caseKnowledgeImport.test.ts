import { describe, expect, it } from 'vitest'
import type { HistoricalCaseKnowledgeImportResult } from '@/api'
import {
  caseKnowledgeImportCanSubmit,
  caseKnowledgeImportSummary,
  caseKnowledgeVectorMessage,
} from '../caseKnowledgeImport'

describe('historical troubleshooting case knowledge import', () => {
  it('requires an existing knowledge base and an admin action', () => {
    expect(caseKnowledgeImportCanSubmit('', 100, true)).toBe(false)
    expect(caseKnowledgeImportCanSubmit('42', 0, true)).toBe(false)
    expect(caseKnowledgeImportCanSubmit('42', 201, true)).toBe(false)
    expect(caseKnowledgeImportCanSubmit('42', 100, false)).toBe(false)
    expect(caseKnowledgeImportCanSubmit('42', 100, true)).toBe(true)
  })

  it('summarizes storage and vector readiness separately', () => {
    const result: HistoricalCaseKnowledgeImportResult = {
      knowledgeBaseId: '42',
      discovered: 15,
      imported: 12,
      reused: 2,
      vectorReady: 9,
      vectorPending: 5,
      failed: 1,
      items: [],
    }

    expect(caseKnowledgeImportSummary(result)).toBe(
      '发现 15 条：新增 12，复用 2，失败 1',
    )
    expect(caseKnowledgeVectorMessage(result)).toEqual({
      tone: 'warning',
      text: '向量已就绪 9 条，待生成 5 条。素材已入库，但待生成的部分暂不能语义检索。',
    })
  })

  it('reports a fully searchable import only when every stored case has vectors', () => {
    const result: HistoricalCaseKnowledgeImportResult = {
      knowledgeBaseId: '42',
      discovered: 2,
      imported: 2,
      reused: 0,
      vectorReady: 2,
      vectorPending: 0,
      failed: 0,
      items: [],
    }

    expect(caseKnowledgeVectorMessage(result)).toEqual({
      tone: 'success',
      text: '向量已就绪 2 条，可用于语义检索与成功案例参考。',
    })
  })
})
