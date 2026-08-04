import type { HistoricalCaseKnowledgeImportResult } from '@/api'

export const DEFAULT_CASE_KNOWLEDGE_IMPORT_LIMIT = 100
export const MAX_CASE_KNOWLEDGE_IMPORT_LIMIT = 200

export function caseKnowledgeImportCanSubmit(
  knowledgeBaseId: string | number | null | undefined,
  limit: number,
  canManage: boolean,
) {
  return canManage
    && String(knowledgeBaseId ?? '').trim().length > 0
    && Number.isInteger(limit)
    && limit >= 1
    && limit <= MAX_CASE_KNOWLEDGE_IMPORT_LIMIT
}

export function caseKnowledgeImportSummary(result: HistoricalCaseKnowledgeImportResult) {
  return `发现 ${result.discovered} 条：新增 ${result.imported}，复用 ${result.reused}，失败 ${result.failed}`
}

export function caseKnowledgeVectorMessage(result: HistoricalCaseKnowledgeImportResult): {
  tone: 'success' | 'warning'
  text: string
} {
  if (result.vectorPending === 0 && result.failed === 0) {
    return {
      tone: 'success',
      text: `向量已就绪 ${result.vectorReady} 条，可用于语义检索与成功案例参考。`,
    }
  }
  return {
    tone: 'warning',
    text: `向量已就绪 ${result.vectorReady} 条，待生成 ${result.vectorPending} 条。素材已入库，但待生成的部分暂不能语义检索。`,
  }
}
