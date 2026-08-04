import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 「组件写了，但没有挂到任何一个真实屏幕上」是一类后端完全看不见的缺陷。
 *
 * <p>接口返回得好好的，组件自己的渲染测试也全绿，可用户永远看不到它。
 * 本轮做的调查透明化（谁参与了、谁刻意没参与）如果掉在这里，页面上就只剩结论
 * 而没有出处，而那正是这块功能存在的全部理由。</p>
 *
 * <p><b>这条用例证明什么、不证明什么。</b> 它读源码，确认父组件既 import 了子组件
 * 也在模板里放了它——能挡住「被删掉」和「import 了却没用」。它**不**证明这块区域
 * 在真实数据下渲染出了像素；那需要把 11 个 prop 的父组件整个挂起来，而那样的用例
 * 会因为无关原因变红，进而训练人去削弱它。分量与它能证明的东西相称，比假装更强要好。</p>
 */
describe('the investigation provenance panel is actually on a screen', () => {
  const CHILD = 'InvestigationProvenancePanel'

  it('is imported and placed by the developer evidence panel', () => {
    const source = read('../DeveloperEvidencePanel.vue')

    expect(source).toContain(`import ${CHILD} from './${CHILD}.vue'`)
    expect(source).toMatch(new RegExp(`<${CHILD}[\\s>]`))
  })

  /** 父组件自己也必须挂在正式工作台上，否则这条链还是断的。 */
  it('reaches the formal workbench through the developer evidence panel', () => {
    const workbench = read('../FormalWorkbench.vue')

    expect(workbench).toContain("import DeveloperEvidencePanel from './DeveloperEvidencePanel.vue'")
    expect(workbench).toMatch(/<DeveloperEvidencePanel[\s>]/)
  })

  function read(relative: string): string {
    return readFileSync(
      fileURLToPath(new URL(relative, import.meta.url)),
      'utf8',
    )
  }
})
