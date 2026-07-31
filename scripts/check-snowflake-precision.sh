#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ui_source_dir="${script_dir}/../mateclaw-ui/src"

node - "${ui_source_dir}" <<'NODE'
const fs = require('node:fs')
const path = require('node:path')

const sourceRoot = path.resolve(process.argv[2])
const ts = require(path.join(sourceRoot, '..', 'node_modules', 'typescript'))
const sourceExtensions = new Set(['.ts', '.tsx', '.js', '.jsx', '.vue'])
const allowMarker = 'snowflake-precision-ok'
const numericCoercions = new Set(['Number', 'parseInt', 'parseFloat'])
const idName = /^(?:id|ids|ID|IDs|[A-Za-z_$][\w$]*(?:Id|ID|Ids|IDs)|[A-Za-z_$][\w$]*_(?:id|ids))$/
const idExpression = /(?:^|[^\w$])(?:id|ids|ID|IDs|[A-Za-z_$][\w$]*(?:Id|ID|Ids|IDs)|[A-Za-z_$][\w$]*_(?:id|ids))(?:$|[^\w$])/
const allowPattern = /snowflake-precision-ok\s*:\s*\S+/

function sourceFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(absolute)
    return sourceExtensions.has(path.extname(entry.name)) ? [absolute] : []
  })
}

function lineNumber(content, offset) {
  return content.slice(0, offset).split(/\r?\n/).length
}

function sourceLine(content, offset) {
  return content.split(/\r?\n/)[lineNumber(content, offset) - 1] || ''
}

function allowedAt(content, offset) {
  const lines = content.split(/\r?\n/)
  const line = lineNumber(content, offset) - 1
  return [lines[line - 1], lines[line]]
    .filter(Boolean)
    .some((candidate) => allowPattern.test(candidate))
}

function containsIdReference(node) {
  let found = false
  function visit(candidate) {
    if (found) return
    if (ts.isIdentifier(candidate) && idName.test(candidate.text)) {
      found = true
      return
    }
    if (ts.isElementAccessExpression(candidate)
        && ts.isStringLiteralLike(candidate.argumentExpression)
        && idName.test(candidate.argumentExpression.text)) {
      found = true
      return
    }
    ts.forEachChild(candidate, visit)
  }
  visit(node)
  return found
}

function scriptKind(file) {
  if (file.endsWith('.tsx')) return ts.ScriptKind.TSX
  if (file.endsWith('.jsx')) return ts.ScriptKind.JSX
  if (file.endsWith('.js')) return ts.ScriptKind.JS
  return ts.ScriptKind.TS
}

function scriptViolations(script, file, fullContent = script, baseOffset = 0) {
  const sourceFile = ts.createSourceFile(file, script, ts.ScriptTarget.Latest, true, scriptKind(file))
  const violations = []
  function visit(node) {
    const directCoercion = ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && numericCoercions.has(node.expression.text)
      && node.arguments.some(containsIdReference)
    const mappedCoercion = ts.isCallExpression(node)
      && ts.isPropertyAccessExpression(node.expression)
      && node.expression.name.text === 'map'
      && containsIdReference(node.expression.expression)
      && node.arguments.some((argument) => ts.isIdentifier(argument)
        && numericCoercions.has(argument.text))
    if (directCoercion || mappedCoercion) {
      const offset = baseOffset + node.getStart(sourceFile)
      if (!allowedAt(fullContent, offset)) {
        violations.push({
          file,
          line: lineNumber(fullContent, offset),
          text: sourceLine(fullContent, offset).trim(),
        })
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sourceFile)
  return violations
}

function withoutHtmlComments(content) {
  return content.replace(/<!--[\s\S]*?-->/g, (comment) => comment.replace(/[^\r\n]/g, ' '))
}

function blankMarkup(content) {
  return content.replace(/[^\r\n]/g, ' ')
}

function vueViolations(content, file) {
  const cleanContent = withoutHtmlComments(content)
  const violations = []
  const scriptBlock = /<script\b[^>]*>([\s\S]*?)<\/script\s*>/gi
  for (const match of cleanContent.matchAll(scriptBlock)) {
    const script = match[1]
    const baseOffset = match.index + match[0].indexOf(script)
    violations.push(...scriptViolations(script, `${file}.ts`, content, baseOffset)
      .map((violation) => ({ ...violation, file })))
  }

  const templateContent = cleanContent.replace(
    /<script\b[^>]*>[\s\S]*?<\/script\s*>/gi,
    blankMarkup,
  )
  const templateExpressions = [
    /{{([\s\S]*?)}}/g,
    /(?:^|\s)(?:[:@#][A-Za-z0-9_$.[\]-]+|v-[A-Za-z0-9_$.:\[\]-]+)\s*=\s*(["'])([\s\S]*?)\1/gm,
  ]
  for (const expressionPattern of templateExpressions) {
    for (const match of templateContent.matchAll(expressionPattern)) {
      const expression = match[2] === undefined ? match[1] : match[2]
      const baseOffset = match.index + match[0].indexOf(expression)
      violations.push(...scriptViolations(expression, `${file}.ts`, content, baseOffset)
        .map((violation) => ({ ...violation, file })))
    }
  }

  const modelDirective = /v-model((?::[A-Za-z0-9_$.-]+)?(?:\.[A-Za-z0-9_-]+)*)\s*=\s*(["'])([\s\S]*?)\2/g
  for (const match of templateContent.matchAll(modelDirective)) {
    const directive = match[1]
    const expression = match[3]
    const modifiers = [...directive.matchAll(/\.([A-Za-z0-9_-]+)/g)].map((item) => item[1])
    const argument = directive.match(/^:([^.]*)/)?.[1] || ''
    const argumentIsId = /(?:^|[-_])ids?$/i.test(argument) || idName.test(argument)
    if (modifiers.includes('number')
        && (argumentIsId || idExpression.test(expression))
        && !allowedAt(content, match.index)) {
      violations.push({
        file,
        line: lineNumber(content, match.index),
        text: match[0].replace(/\s+/g, ' ').trim(),
      })
    }
  }
  return violations
}

function inspect(content, file) {
  return file.endsWith('.vue')
    ? vueViolations(content, file)
    : scriptViolations(content, file)
}

const unsafeChecks = [
  ['sample.ts', 'const value = Number(agentId)'],
  ['sample.ts', 'const value = Number(id)'],
  ['sample.ts', 'const value = Number(ID)'],
  ['sample.ts', 'const value = Number(agent_id)'],
  ['sample.ts', 'const value = parseInt(ids[0], 10)'],
  ['sample.ts', 'const value = Number(\n  payload.agentId\n)'],
  ['sample.ts', 'const value = parseInt(payload.userID, 10)'],
  ['sample.ts', 'const value = fallbackModelIds.value.map(Number)'],
  ['sample.vue', '<input v-model.trim.number="form.agentId">'],
  ['sample.vue', '<input v-model.number="IDs">'],
  ['sample.vue', '<picker v-model:user-id.number="selection">'],
  ['sample.vue', '<div>{{ Number(agentId) }}</div>'],
  ['sample.vue', '<input :value="parseInt(item.ID, 10)">'],
]
const safeChecks = [
  ['sample.ts', '// Number(agentId)'],
  ['sample.ts', '/* Number(agentId) */'],
  ['sample.ts', `// ${allowMarker}: bounded counter\nNumber(sequenceId)`],
  ['sample.ts', 'Number(sequence)'],
  ['sample.vue', '<!-- <input v-model.number="form.agentId"> -->'],
]
if (unsafeChecks.some(([file, content]) => inspect(content, file).length !== 1)
    || safeChecks.some(([file, content]) => inspect(content, file).length !== 0)) {
  throw new Error('Snowflake precision guard self-check failed')
}

const violations = []
for (const file of sourceFiles(sourceRoot)) {
  const content = fs.readFileSync(file, 'utf8')
  violations.push(...inspect(content, file))
}

if (violations.length > 0) {
  console.error('Snowflake precision guard failed.')
  console.error('Keep opaque IDs as strings, or document a proven non-Snowflake value with:')
  console.error(`  // ${allowMarker}: <reason>`)
  for (const violation of violations) {
    console.error(`${path.relative(process.cwd(), violation.file)}:${violation.line}: ${violation.text}`)
  }
  process.exit(1)
}

console.log('Snowflake precision guard passed.')
NODE
