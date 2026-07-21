# MateClaw Agent Instructions

This file is for AI coding agents working in this repository. Follow the local codebase first; avoid broad rewrites unless the user explicitly asks for them.

## Frontend Design System

MateClaw has an existing warm product visual system. New or changed frontend pages must stay inside that system instead of inventing a separate palette.

- Use the theme tokens from `mateclaw-ui/src/assets/main.css`, especially `--mc-bg`, `--mc-bg-*`, `--mc-panel-*`, `--mc-border*`, `--mc-text-*`, `--mc-primary*`, `--mc-accent*`, `--mc-success`, `--mc-warning`, and `--mc-danger`.
- Prefer the shared page shell: `mc-page-shell > mc-page-frame > mc-page-inner`.
- Do not introduce a standalone blue/slate/gray SaaS palette, dark technical console palette, or page-specific brand system unless the task is explicitly to redesign MateClaw's global theme.
- Improve readability with typography, spacing, font weight, line height, wrapping, and existing theme tokens. Do not fix readability by replacing the page with unrelated hard-coded colors.
- For feature pages, match the existing card radius, border weight, shadow, button behavior, sidebar tone, and light/dark theme behavior.
- Hard-coded colors are acceptable only for very narrow cases where no theme token exists yet; prefer adding or reusing a semantic token over scattering literals in page CSS.

## Frontend Verification

Before considering a visible UI change complete:

- Check desktop and narrow mobile widths.
- Ensure there is no document-level horizontal scrolling.
- Ensure operational text such as IDs, commands, logs, status pills, chips, and table metadata remains readable.
- Run the frontend type/build checks when feasible:
  - `node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit`
  - `node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build`

