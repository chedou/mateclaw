import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'

// Two test conventions coexist in this repo:
//   - `test/**/*.test.ts` — pre-existing files using the Node `node:test`
//     runner (run via `node --test test/<file>.test.ts`).
//   - `src/**/__tests__/*.test.ts` — vitest tests for new code.
//
// Scope vitest to `src/**` so it never picks up the node:test files (which
// don't export describe/it/expect and would otherwise fail discovery).
// `@vitejs/plugin-vue` is registered so tests may import `.vue` files and
// actually render them. Without it every test can only reach extracted
// presentation functions — and a whole class of defect lives outside those:
// a component that renders the wrong thing, or one that was written and never
// mounted. No backend test can see either.
export default defineConfig({
  plugins: [vue()],
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'happy-dom',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
})
