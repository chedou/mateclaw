<template>
  <div class="mc-page-shell troubleshooting-shell">
    <div class="mc-page-frame troubleshooting-frame">
      <div class="mc-page-inner troubleshooting-page">
    <div class="mc-page-header compact">
      <div>
        <div class="mc-page-kicker">Troubleshooting SOP</div>
        <h1 class="mc-page-title">排障 SOP 工作台</h1>
        <p class="mc-page-desc">先用告警匹配 SOP，再创建排障 run、采集证据并输出校验报告。</p>
      </div>
      <div class="header-actions">
        <button
          class="btn-primary icon-btn"
          :disabled="demoRunning || routing || mvpLoading || collecting || completing"
          @click="runApi5xxDemo"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M5 12h14" />
            <path d="m13 6 6 6-6 6" />
            <path d="M5 6v12" />
          </svg>
          <span>{{ demoRunning ? '演示中' : '一键演示 API 5xx' }}</span>
        </button>
        <button class="btn-secondary icon-btn" :disabled="loading || demoRunning" @click="loadAll" title="刷新">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 12a9 9 0 0 1-15.5 6.3L3 16" />
            <path d="M3 21v-5h5" />
            <path d="M3 12A9 9 0 0 1 18.5 5.7L21 8" />
            <path d="M21 3v5h-5" />
          </svg>
          <span>刷新</span>
        </button>
      </div>
    </div>

    <div class="sop-metrics" aria-label="SOP metrics">
      <div>
        <span class="metric-value">{{ sops.length }}</span>
        <span class="metric-label">可用 SOP</span>
      </div>
      <div>
        <span class="metric-value">{{ domains.length }}</span>
        <span class="metric-label">故障域</span>
      </div>
      <div>
        <span class="metric-value">{{ expiredCount }}</span>
        <span class="metric-label">待复审</span>
      </div>
    </div>

    <div class="workspace-grid">
      <section class="sop-section sop-list-section">
        <div class="section-head">
          <h2>SOP 列表</h2>
          <input v-model.trim="keyword" class="search-input" placeholder="搜索域、场景、证据、owner" />
        </div>
        <div class="sop-table-wrap">
          <table class="sop-table">
            <thead>
              <tr>
                <th>故障域</th>
                <th>场景</th>
                <th>证据</th>
                <th>Owner</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!filteredSops.length">
                <td colspan="5" class="empty-cell">暂无匹配的 SOP</td>
              </tr>
              <tr
                v-for="sop in filteredSops"
                :key="String(sop.skillId)"
                :class="{ selected: selectedSop?.skillId === sop.skillId }"
                @click="selectedSop = sop"
              >
                <td>
                  <strong>{{ sop.domain }}</strong>
                  <span class="muted">{{ sop.name }}</span>
                </td>
                <td>{{ sop.scenario }}</td>
                <td>
                  <div class="chips">
                    <span v-for="item in sop.requiredEvidence" :key="item" class="chip required">{{ item }}</span>
                    <span v-for="item in sop.optionalEvidence" :key="item" class="chip">{{ item }}</span>
                  </div>
                </td>
                <td>{{ sop.owner || '-' }}</td>
                <td>
                  <span class="status-pill" :class="{ warn: sop.expired }">
                    {{ sop.expired ? '待复审' : '有效' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="sop-section route-section">
        <div class="section-head">
          <div class="section-title">
            <h2>告警匹配 SOP</h2>
            <p>输入告警内容，预览系统会选择哪个排障 SOP；不会创建案件或执行采集。</p>
          </div>
          <button class="btn-primary icon-btn" :disabled="routing || demoRunning" @click="previewRoute">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.3-4.3" />
            </svg>
            <span>匹配 SOP</span>
          </button>
        </div>

        <div class="route-form">
          <label>级别 <input v-model="previewForm.severity" placeholder="P1 / P2 / critical" /></label>
          <label>服务 <input v-model="previewForm.serviceName" placeholder="sf-icare-exchange" /></label>
          <label>环境 <input v-model="previewForm.env" placeholder="prod" /></label>
          <label>集群 <input v-model="previewForm.cluster" placeholder="bwx-prod-k8s" /></label>
          <label>端点 <input v-model="previewForm.endpoint" placeholder="/api/order/list" /></label>
          <label>指标 <input v-model="previewForm.metricName" placeholder="http_5xx_rate" /></label>
          <label class="span-2">告警名 <input v-model="previewForm.alertName" placeholder="API 5xx error rate high" /></label>
          <label class="span-2">消息 <textarea v-model="previewForm.message" rows="3" placeholder="粘贴告警正文、企业微信卡片文本或 webhook message" /></label>
          <label class="span-2">Labels JSON <textarea v-model="labelsText" rows="3" placeholder='{"namespace":"default","endpoint":"/api/demo"}' /></label>
        </div>

        <div v-if="routeResult" class="route-result">
          <div class="result-summary" :class="{ fallback: routeResult.usedFallback }">
            <span class="result-label">推荐 SOP</span>
            <strong v-if="routeResult.selected">
              {{ routeResult.selected.domain }}/{{ routeResult.selected.scenario }}
            </strong>
            <strong v-else>未命中</strong>
            <span v-if="routeResult.selected" class="confidence">
              {{ percent(routeResult.selected.confidence) }}
            </span>
          </div>
          <div class="candidate-list">
            <article v-for="candidate in routeResult.candidates" :key="String(candidate.skillId)" class="candidate-row">
              <div>
                <strong>{{ candidate.domain }}/{{ candidate.scenario }}</strong>
                <span>{{ candidate.reason }}</span>
              </div>
              <span class="score">{{ Math.round(candidate.score) }}</span>
            </article>
          </div>
        </div>
      </section>
    </div>

    <section class="sop-section guance-section">
      <div class="section-head">
        <div class="section-title">
          <h2>观测云查询模板</h2>
          <p>把拨测 payload、DQL 和匹配规则写入数据库；SOP 采集时按 matchJson 自动选择，也支持 labels 强制指定模板。</p>
        </div>
        <div class="header-actions">
          <label class="compact-select">
            <span>证据类型</span>
            <select v-model="queryTemplateEvidenceType" @change="onQueryTemplateEvidenceTypeChange">
              <option v-for="option in queryTemplateEvidenceTypes" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <button class="btn-secondary icon-btn" :disabled="queryTemplatesLoading" @click="() => loadQueryTemplates()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 12a9 9 0 0 1-15.5 6.3L3 16" />
              <path d="M3 21v-5h5" />
              <path d="M3 12A9 9 0 0 1 18.5 5.7L21 8" />
              <path d="M21 3v5h-5" />
            </svg>
            <span>刷新模板</span>
          </button>
          <button class="btn-secondary icon-btn" :disabled="queryTemplateSeeding" @click="seedGuanceDefaultTemplates">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 3v18" />
              <path d="M17 8H9.5a3.5 3.5 0 0 0 0 7H15a3 3 0 0 1 0 6H6" />
            </svg>
            <span>{{ queryTemplateSeeding ? '初始化中' : '补齐默认模板' }}</span>
          </button>
          <button class="btn-secondary icon-btn" @click="newQueryTemplate">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 12h14" />
              <path d="M12 5v14" />
            </svg>
            <span>新建</span>
          </button>
          <button class="btn-primary icon-btn" @click="seedGuanceDialTemplate">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 4h16v16H4z" />
              <path d="M8 9h8" />
              <path d="M8 13h5" />
              <path d="M8 17h8" />
            </svg>
            <span>填入默认模板</span>
          </button>
        </div>
      </div>

      <div class="guance-connector-card">
        <div class="mvp-card-head">
          <span>连接配置</span>
          <strong>{{ guanceConfig?.enabled ? '已启用' : '未启用' }}</strong>
          <span class="status-pill" :class="{ warn: !guanceConfig?.tokenConfigured, info: guanceConfig?.persisted }">
            {{ guanceConfig?.tokenConfigured ? `Token: ${guanceConfig.tokenSource}` : 'Token 未配置' }}
          </span>
        </div>
        <div class="connector-form-grid">
          <div class="template-switches">
            <label><input v-model="guanceConfigForm.enabled" type="checkbox" /> 启用观测云</label>
          </div>
          <label>Base URL <input v-model.trim="guanceConfigForm.baseUrl" placeholder="http://df-openapi.prd.sangfor.com" /></label>
          <label>拨测 Path <input v-model.trim="guanceConfigForm.syntheticsPath" placeholder="/api/v1/df/query_data_v1" /></label>
          <label>Metrics Path <input v-model.trim="guanceConfigForm.metricsPath" placeholder="/api/v1/df/query_data_v1" /></label>
          <label>Token Header <input v-model.trim="guanceConfigForm.tokenHeader" placeholder="DF-API-KEY" /></label>
          <label>Token Prefix <input v-model="guanceConfigForm.tokenPrefix" placeholder="Bearer " /></label>
          <label>API Token <input v-model.trim="guanceConfigForm.token" type="password" :placeholder="guanceConfig?.tokenConfigured ? '留空则保留当前 token' : '粘贴观测云 API Key'" /></label>
          <label>拨测 Limit <input v-model.number="guanceConfigForm.syntheticsLimit" type="number" min="1" /></label>
          <label>Metrics Limit <input v-model.number="guanceConfigForm.metricsLimit" type="number" min="1" /></label>
          <label>响应预览长度 <input v-model.number="guanceConfigForm.maxResponseChars" type="number" min="256" /></label>
        </div>
        <div class="template-actions">
          <button class="btn-secondary icon-btn" :disabled="guanceConfigLoading" @click="() => loadGuanceConnectorConfig()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 12a9 9 0 0 1-15.5 6.3L3 16" />
              <path d="M3 21v-5h5" />
              <path d="M3 12A9 9 0 0 1 18.5 5.7L21 8" />
              <path d="M21 3v5h-5" />
            </svg>
            <span>刷新连接</span>
          </button>
          <button class="btn-secondary icon-btn" @click="seedGuanceConnectorConfig">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 4h16v16H4z" />
              <path d="M8 8h8" />
              <path d="M8 12h8" />
              <path d="M8 16h5" />
            </svg>
            <span>填入观测云 OpenAPI</span>
          </button>
          <button class="btn-primary icon-btn" :disabled="guanceConfigSaving" @click="saveGuanceConnectorConfig">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
              <path d="M17 21v-8H7v8" />
              <path d="M7 3v5h8" />
            </svg>
            <span>{{ guanceConfigSaving ? '保存中' : '保存连接配置' }}</span>
          </button>
        </div>
      </div>

      <div class="guance-grid">
        <div class="template-list">
          <div v-if="!queryTemplates.length && !queryTemplatesLoading" class="empty-cell compact-empty">
            暂无数据库模板。可以先选择证据类型，填入默认模板后保存。
          </div>
          <button
            v-for="template in queryTemplates"
            :key="String(template.id)"
            class="template-row"
            :class="{ selected: sameId(template.id, selectedQueryTemplateId) }"
            @click="editQueryTemplate(template)"
          >
            <span class="template-row-main">
              <strong>{{ template.templateKey }}</strong>
              <span>{{ template.name }}</span>
            </span>
            <span class="template-row-meta">
              <span class="status-pill" :class="{ warn: !template.enabled, info: template.defaultTemplate }">
                {{ template.enabled ? (template.defaultTemplate ? '默认' : '启用') : '停用' }}
              </span>
              <span>{{ template.provider }}/{{ template.evidenceType }}</span>
            </span>
          </button>
        </div>

        <div class="template-editor">
          <div class="template-form-grid">
            <label>模板 Key <input v-model.trim="queryTemplateForm.templateKey" placeholder="guance-http-dial-by-name" /></label>
            <label>名称 <input v-model.trim="queryTemplateForm.name" placeholder="观测云 HTTP 拨测 - 按任务名" /></label>
            <label>Provider <input v-model.trim="queryTemplateForm.provider" placeholder="guance" /></label>
            <label>证据类型 <input v-model.trim="queryTemplateForm.evidenceType" placeholder="synthetics" /></label>
            <label>优先级 <input v-model.number="queryTemplateForm.priority" type="number" min="0" /></label>
            <div class="template-switches">
              <label><input v-model="queryTemplateForm.enabled" type="checkbox" /> 启用</label>
              <label><input v-model="queryTemplateForm.defaultTemplate" type="checkbox" /> 默认模板</label>
            </div>
            <label class="span-2">说明 <input v-model.trim="queryTemplateForm.description" placeholder="适用于可用性检测 > 任务 > HTTP 拨测结果查询" /></label>
            <label class="span-2">
              匹配规则 JSON
              <textarea
                v-model="queryTemplateForm.matchJson"
                rows="6"
                spellcheck="false"
                placeholder='{"labelExists":["syntheticsTaskName"],"labels":{"region":"malaysia"},"keywords":["拨测"]}'
              ></textarea>
            </label>
            <label class="span-2">
              DQL 模板
              <textarea v-model="queryTemplateForm.dqlTemplate" rows="3" spellcheck="false"></textarea>
            </label>
            <label class="span-2">
              Payload 模板 JSON
              <textarea v-model="queryTemplateForm.payloadTemplate" rows="12" spellcheck="false"></textarea>
            </label>
          </div>
          <div class="template-actions">
            <button class="btn-primary icon-btn" :disabled="queryTemplateSaving" @click="saveQueryTemplate">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
                <path d="M17 21v-8H7v8" />
                <path d="M7 3v5h8" />
              </svg>
              <span>{{ selectedQueryTemplateId ? '保存修改' : '保存为新模板' }}</span>
            </button>
            <button class="btn-secondary icon-btn" :disabled="!queryTemplateForm.templateKey" @click="applyQueryTemplateToLabels">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20 6 9 17l-5-5" />
              </svg>
              <span>写入当前告警 Labels</span>
            </button>
            <button class="btn-secondary icon-btn" :disabled="queryTemplatePreviewLoading" @click="previewQueryTemplate">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M15 3h6v6" />
                <path d="M10 14 21 3" />
                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
              </svg>
              <span>{{ queryTemplatePreviewLoading ? '试查中' : '试查观测云' }}</span>
            </button>
            <button class="btn-secondary icon-btn danger" :disabled="!selectedQueryTemplateId || queryTemplateSaving" @click="deleteQueryTemplate">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 6h18" />
                <path d="M8 6V4h8v2" />
                <path d="M19 6l-1 14H6L5 6" />
              </svg>
              <span>删除</span>
            </button>
          </div>
          <div v-if="queryTemplatePreview" class="template-preview-panel" :class="{ warn: queryTemplatePreview.status !== 'collected' }">
            <div class="mvp-card-head">
              <span>试查结果</span>
              <strong>{{ queryTemplatePreview.status }}</strong>
              <span class="status-pill" :class="{ warn: queryTemplatePreview.status !== 'collected' }">
                {{ queryTemplatePreview.source }}
              </span>
            </div>
            <p>{{ queryTemplatePreview.summary || queryTemplatePreview.error || '-' }}</p>
            <div class="mvp-meta">
              <span>endpoint：{{ queryTemplatePreview.endpoint || '-' }}</span>
              <span>duration：{{ queryTemplatePreview.durationMs ?? '-' }}ms</span>
            </div>
            <pre class="report-box">{{ formatJson({
              request: queryTemplatePreview.request,
              normalized: queryTemplatePreview.normalized,
              responsePreview: queryTemplatePreview.responsePreview,
              error: queryTemplatePreview.error,
            }) }}</pre>
          </div>
        </div>
      </div>
    </section>

    <section class="sop-section mvp-section">
      <div class="section-head">
        <div class="section-title">
          <h2>排障演练</h2>
          <p>从当前告警创建 run，按 SOP 采集证据、生成 checklist 草稿并运行校验。</p>
        </div>
        <div class="case-search">
          <input v-model.trim="caseId" placeholder="caseId，留空自动生成" />
          <button class="btn-primary icon-btn" :disabled="mvpLoading || demoRunning" @click="createMvpRun">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 12h14" />
              <path d="M12 5v14" />
            </svg>
            <span>创建排障 Run</span>
          </button>
          <button class="btn-secondary icon-btn" :disabled="collecting || !mvpRun || demoRunning" @click="collectMvpEvidence">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 7h16" />
              <path d="M4 12h10" />
              <path d="M4 17h7" />
              <path d="m17 14 3 3 3-3" />
            </svg>
            <span>采集证据</span>
          </button>
          <button class="btn-secondary icon-btn" :disabled="completing || !mvpRun || demoRunning" @click="completeMvpRun">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M20 6 9 17l-5-5" />
            </svg>
            <span>提交校验</span>
          </button>
        </div>
      </div>

      <div v-if="demoStage" class="demo-strip" :class="{ done: demoStage === 'done', warn: demoStage === 'error' }">
        <div class="demo-status">
          <strong>{{ demoStageTitle }}</strong>
          <span>{{ demoStageText }}</span>
        </div>
        <div class="demo-steps">
          <span
            v-for="step in demoSteps"
            :key="step.key"
            class="demo-step"
            :class="demoStepClass(step.key)"
          >
            {{ step.label }}
          </span>
        </div>
      </div>

      <div v-if="!startResult" class="empty-cell compact-empty">
        先在“告警匹配 SOP”里确认推荐 SOP，再创建排障 run；系统会生成 Agent 执行合同和结构化 checklist 草稿。
      </div>
      <div v-else class="mvp-grid">
        <article class="mvp-card">
          <div class="mvp-card-head">
            <span>Run</span>
            <strong>{{ startResult.run.domain }}/{{ startResult.run.scenario }}</strong>
            <span class="status-pill" :class="{ warn: startResult.run.status === 'evidence_insufficient' }">
              {{ startResult.run.status }}
            </span>
          </div>
          <div class="mvp-meta">
            <span>caseId：{{ startResult.run.caseId }}</span>
            <span>runId：{{ startResult.run.id }}</span>
            <span>confidence：{{ percent(startResult.run.confidence) }}</span>
          </div>
        </article>

        <article v-if="mvpEvidenceCoverage.length" class="mvp-card report-card evidence-gap-card">
          <div class="mvp-card-head">
            <span>证据覆盖</span>
            <strong>{{ evidenceCoverageSummary(mvpEvidenceCoverage) }}</strong>
          </div>
          <div class="evidence-gap-grid">
            <article
              v-for="item in mvpEvidenceCoverage"
              :key="item.type"
              class="evidence-gap-item"
              :class="item.tone"
            >
              <div class="gap-item-head">
                <strong>{{ item.type }}</strong>
                <span class="status-pill" :class="{ warn: item.tone === 'warn', neutral: item.tone === 'neutral', info: item.tone === 'info' }">
                  {{ item.label }}
                </span>
              </div>
              <p>{{ item.detail }}</p>
              <div v-if="item.evidenceIds.length" class="chips">
                <span v-for="id in item.evidenceIds" :key="id" class="chip">{{ id }}</span>
              </div>
            </article>
          </div>
        </article>

        <article class="mvp-card prompt-card">
          <div class="mvp-card-head">
            <span>Agent 执行合同</span>
          </div>
          <textarea class="prompt-box" readonly :value="startResult.executionPrompt"></textarea>
        </article>

        <article v-if="evidenceRecords.length" class="mvp-card report-card">
          <div class="mvp-card-head">
            <span>Evidence Records</span>
            <strong>{{ evidenceRecords.length }} 条</strong>
          </div>
          <div class="evidence-list">
            <article v-for="evidence in evidenceRecords" :key="String(evidence.id)" class="evidence-card">
              <div class="step-head">
                <strong>{{ evidence.evidenceType }}</strong>
                <span class="status-pill">{{ evidence.status }}</span>
                <span class="decision-pill">{{ evidence.source }}</span>
              </div>
              <span class="muted">{{ evidence.evidenceId }} · {{ evidence.title || '-' }}</span>
              <p>{{ evidence.summary }}</p>
              <div v-if="evidenceHighlights(evidence).length" class="evidence-highlights">
                <span v-for="item in evidenceHighlights(evidence)" :key="item" class="evidence-highlight">{{ item }}</span>
              </div>
              <ul v-if="evidencePreviewLines(evidence).length" class="evidence-preview">
                <li v-for="line in evidencePreviewLines(evidence)" :key="line">{{ line }}</li>
              </ul>
            </article>
          </div>
        </article>

        <article class="mvp-card">
          <div class="mvp-card-head">
            <span>stepResults 草稿 JSON</span>
            <button class="text-button" @click="resetMvpPayload">重置草稿</button>
          </div>
          <textarea v-model="stepResultsText" class="json-editor" spellcheck="false"></textarea>
        </article>

        <article class="mvp-card">
          <div class="mvp-card-head">
            <span>finalReport JSON</span>
          </div>
          <textarea v-model="finalReportText" class="json-editor" spellcheck="false"></textarea>
        </article>

        <article v-if="completeResult" class="mvp-card report-card">
          <div class="mvp-card-head">
            <span>Validator</span>
            <span class="status-pill" :class="{ warn: !completeResult.validation.valid }">
              {{ completeResult.validation.valid ? '通过' : '证据不足' }}
            </span>
          </div>
          <div v-if="completeResult.validation.errors.length" class="validation-list">
            {{ completeResult.validation.errors.join(', ') }}
          </div>
          <pre class="report-box">{{ completeResult.groupReport }}</pre>
        </article>
      </div>
    </section>

    <section class="sop-section run-section">
      <div class="section-head">
        <h2>案件 SOP 轨迹</h2>
        <div class="case-search">
          <input v-model.trim="caseId" placeholder="输入 caseId" @keyup.enter="loadRuns" />
          <button class="btn-secondary icon-btn" :disabled="runsLoading || !caseId" @click="loadRuns">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <path d="M14 2v6h6" />
              <path d="M16 13H8" />
              <path d="M16 17H8" />
              <path d="M10 9H8" />
            </svg>
            <span>查询</span>
          </button>
        </div>
      </div>
      <div class="run-list">
        <div v-if="!caseId" class="empty-cell">输入 caseId 后查看 SOP run。</div>
        <div v-else-if="!sopRuns.length && !runsLoading" class="empty-cell">该案件暂无 SOP run。</div>
        <article
          v-for="run in sopRuns"
          :key="String(run.id)"
          class="run-row"
          :class="{ selected: isSelectedRun(run) }"
          @click="toggleRun(run)"
        >
          <div class="run-summary">
            <div class="run-main">
              <strong>{{ run.domain || '-' }}/{{ run.scenario || '-' }}</strong>
              <span>{{ run.routeReason || '无路由说明' }}</span>
            </div>
            <div class="run-meta">
              <span class="status-pill" :class="{ warn: run.status === 'evidence_insufficient' }">{{ run.status }}</span>
              <span>{{ run.completedAt || run.startedAt || run.createTime || '-' }}</span>
            </div>
          </div>

          <div v-if="isSelectedRun(run)" class="run-detail" @click.stop>
            <div class="run-detail-grid">
              <span>runId：{{ run.id }}</span>
              <span>sop：{{ run.sopName || '-' }} v{{ run.sopVersion || '-' }}</span>
              <span>confidence：{{ percent(run.confidence) }}</span>
            </div>

            <div v-if="parseRunValidation(run)" class="validation-list">
              <strong>Validator：</strong>
              <span v-if="parseRunValidation(run)?.valid">通过</span>
              <span v-else>
                证据不足
                <template v-if="parseRunValidation(run)?.missingEvidence?.length">
                  ，缺少 {{ parseRunValidation(run)?.missingEvidence.join(', ') }}
                </template>
              </span>
              <span v-if="parseRunValidation(run)?.errors?.length">
                ，{{ parseRunValidation(run)?.errors.join(', ') }}
              </span>
            </div>

            <div v-if="evidenceLoading" class="empty-cell compact-empty">正在加载 evidence...</div>
            <div v-if="!evidenceLoading && runEvidenceCoverage(run).length" class="evidence-gap-panel">
              <div class="mvp-card-head compact-gap-head">
                <span>证据覆盖</span>
                <strong>{{ evidenceCoverageSummary(runEvidenceCoverage(run)) }}</strong>
              </div>
              <div class="evidence-gap-grid">
                <article
                  v-for="item in runEvidenceCoverage(run)"
                  :key="item.type"
                  class="evidence-gap-item"
                  :class="item.tone"
                >
                  <div class="gap-item-head">
                    <strong>{{ item.type }}</strong>
                    <span class="status-pill" :class="{ warn: item.tone === 'warn', neutral: item.tone === 'neutral', info: item.tone === 'info' }">
                      {{ item.label }}
                    </span>
                  </div>
                  <p>{{ item.detail }}</p>
                </article>
              </div>
            </div>
            <div v-if="!evidenceLoading && selectedRunEvidence.length" class="evidence-list">
              <article v-for="evidence in selectedRunEvidence" :key="String(evidence.id)" class="evidence-card">
                <div class="step-head">
                  <strong>{{ evidence.evidenceType }}</strong>
                  <span class="status-pill">{{ evidence.status }}</span>
                  <span class="decision-pill">{{ evidence.source }}</span>
                </div>
                <span class="muted">{{ evidence.evidenceId }} · {{ evidence.title || '-' }}</span>
                <p>{{ evidence.summary }}</p>
                <div v-if="evidenceHighlights(evidence).length" class="evidence-highlights">
                  <span v-for="item in evidenceHighlights(evidence)" :key="item" class="evidence-highlight">{{ item }}</span>
                </div>
                <ul v-if="evidencePreviewLines(evidence).length" class="evidence-preview">
                  <li v-for="line in evidencePreviewLines(evidence)" :key="line">{{ line }}</li>
                </ul>
              </article>
            </div>

            <div v-if="parseRunSteps(run).length" class="step-timeline">
              <article v-for="step in parseRunSteps(run)" :key="step.stepId" class="step-card">
                <div class="step-head">
                  <strong>{{ step.stepId }}</strong>
                  <span class="status-pill" :class="{ warn: step.status === 'inconclusive' || step.status === 'failed' }">
                    {{ step.status }}
                  </span>
                  <span class="decision-pill">{{ step.nextDecision }}</span>
                </div>
                <div class="chips">
                  <span v-for="evidenceId in step.evidenceIds || []" :key="evidenceId" class="chip">{{ evidenceId }}</span>
                  <span v-for="evidenceType in step.evidenceTypes || []" :key="evidenceType" class="chip required">{{ evidenceType }}</span>
                </div>
                <p>{{ step.observation }}</p>
                <p class="muted">{{ step.interpretation }}</p>
              </article>
            </div>
            <div v-else class="empty-cell compact-empty">该 run 尚未提交 stepResults。</div>

            <pre v-if="runGroupReport(run)" class="report-box">{{ runGroupReport(run) }}</pre>
          </div>
        </article>
      </div>
    </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { mcToast } from '@/composables/useMcToast'
import {
  troubleshootingApi,
  type TroubleshootingRouteRequest,
  type TroubleshootingRouteResult,
  type TroubleshootingEvidenceRecord,
  type TroubleshootingSopRunCompleteResponse,
  type TroubleshootingSopRunStartResponse,
  type TroubleshootingSopRun,
  type TroubleshootingSopStepResult,
  type TroubleshootingSopValidationResult,
  type TroubleshootingSopSummary,
  type TroubleshootingConnectorConfig,
  type TroubleshootingConnectorConfigRequest,
  type TroubleshootingQueryTemplate,
  type TroubleshootingQueryTemplatePreviewResponse,
  type TroubleshootingQueryTemplateRequest,
} from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

type EvidenceCoverageTone = 'ok' | 'info' | 'neutral' | 'warn'

interface EvidenceCoverageItem {
  type: string
  label: string
  detail: string
  tone: EvidenceCoverageTone
  evidenceIds: string[]
}

const defaultGuancePayloadTemplate = `{
  "queries": [
    {
      "qtype": "dql",
      "query": {
        "q": "\${dqlQuery}",
        "_funcList": [],
        "funcList": [],
        "maxPointCount": 720,
        "interval": 10,
        "align_time": true,
        "sorder_by": [],
        "slimit": \${limit},
        "disable_sampling": false,
        "timeRange": [],
        "tz": "Asia/Shanghai"
      }
    }
  ]
}`
const defaultGuanceDqlTemplate = "D::http_dial_testing:(`status_code`, `url`, `name`) { `name` = '${syntheticsTaskNameDql}' }"
const defaultGuanceMatchJson = `{
  "labelExists": ["syntheticsTaskName"],
  "metricNames": ["synthetics_status_code"],
  "keywords": ["可用性检测", "拨测", "http_dial_testing"]
}`
type QueryTemplateEvidenceType = 'synthetics' | 'host' | 'container' | 'k8s' | 'metrics'
const queryTemplateEvidenceTypes: Array<{ value: QueryTemplateEvidenceType; label: string }> = [
  { value: 'synthetics', label: '可用性拨测' },
  { value: 'host', label: '基础设施主机' },
  { value: 'container', label: '基础设施容器' },
  { value: 'k8s', label: 'K8s 事件' },
  { value: 'metrics', label: '指标查询' },
]
const defaultGuanceDqlTemplates: Record<QueryTemplateEvidenceType, string> = {
  synthetics: defaultGuanceDqlTemplate,
  host: "D::host:(`host`, `host_name`, `ip`, `cpu_usage`, `mem_used_percent`, `status`) { `host` = '${hostNameDql}' }",
  container: "D::container:(`container_name`, `pod_name`, `namespace`, `cluster`, `status`, `restart_count`) { `pod_name` = '${podNameDql}' }",
  k8s: "D::container:(`container_name`, `pod_name`, `namespace`, `cluster`, `status`, `restart_count`) { `namespace` = '${namespaceDql}' }",
  metrics: "M::`${metricNameIdentifier}`:(*) { `service` = '${serviceNameDql}' }",
}
const defaultGuanceMatchJsonTemplates: Record<QueryTemplateEvidenceType, string> = {
  synthetics: defaultGuanceMatchJson,
  host: `{
  "hostNames": ["*"],
  "keywords": ["主机", "host", "cpu", "memory"]
}`,
  container: `{
  "podNames": ["*"],
  "keywords": ["容器", "container", "pod", "restart"]
}`,
  k8s: `{
  "namespaces": ["*"],
  "keywords": ["k8s", "pod", "restart", "probe"]
}`,
  metrics: `{
  "keywords": ["指标", "metrics", "5xx", "latency", "timeout"]
}`,
}

const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => workspaceStore.currentWorkspaceId)

const loading = ref(false)
const routing = ref(false)
const runsLoading = ref(false)
const mvpLoading = ref(false)
const completing = ref(false)
const collecting = ref(false)
const evidenceLoading = ref(false)
const demoRunning = ref(false)
const sops = ref<TroubleshootingSopSummary[]>([])
const selectedSop = ref<TroubleshootingSopSummary | null>(null)
const routeResult = ref<TroubleshootingRouteResult | null>(null)
const startResult = ref<TroubleshootingSopRunStartResponse | null>(null)
const completeResult = ref<TroubleshootingSopRunCompleteResponse | null>(null)
const sopRuns = ref<TroubleshootingSopRun[]>([])
const selectedRunId = ref<string | number | null>(null)
const evidenceRecords = ref<TroubleshootingEvidenceRecord[]>([])
const selectedRunEvidence = ref<TroubleshootingEvidenceRecord[]>([])
const keyword = ref('')
const caseId = ref('')
const stepResultsText = ref('')
const finalReportText = ref('')
const queryTemplates = ref<TroubleshootingQueryTemplate[]>([])
const queryTemplatesLoading = ref(false)
const queryTemplateSaving = ref(false)
const queryTemplateSeeding = ref(false)
const queryTemplatePreviewLoading = ref(false)
const queryTemplatePreview = ref<TroubleshootingQueryTemplatePreviewResponse | null>(null)
const selectedQueryTemplateId = ref<string | number | null>(null)
const queryTemplateEvidenceType = ref<QueryTemplateEvidenceType>('synthetics')
const queryTemplateForm = ref<TroubleshootingQueryTemplateRequest>(blankGuanceQueryTemplate('synthetics'))
const guanceConfig = ref<TroubleshootingConnectorConfig | null>(null)
const guanceConfigLoading = ref(false)
const guanceConfigSaving = ref(false)
const guanceConfigForm = ref<TroubleshootingConnectorConfigRequest>(blankGuanceConnectorConfig())

const demoSteps = [
  { key: 'fill', label: '填告警' },
  { key: 'route', label: '匹配' },
  { key: 'run', label: '建案件' },
  { key: 'evidence', label: '采证据' },
  { key: 'validate', label: '校验' },
] as const
type DemoStage = '' | 'error' | 'done' | typeof demoSteps[number]['key']
type DemoStepKey = typeof demoSteps[number]['key']
const demoStage = ref<DemoStage>('')

const previewForm = ref<TroubleshootingRouteRequest>({
  severity: 'P2',
  serviceName: '',
  env: 'prod',
  cluster: '',
  endpoint: '',
  metricName: '',
  alertName: '',
  message: '',
  topK: 5,
})
const labelsText = ref('{\n  "namespace": "",\n  "pod": ""\n}')

const domains = computed(() => [...new Set(sops.value.map((sop) => sop.domain))])
const expiredCount = computed(() => sops.value.filter((sop) => sop.expired).length)
const mvpRun = computed(() => startResult.value?.run || null)
const demoStageTitle = computed(() => {
  if (demoStage.value === 'done') return '演示完成'
  if (demoStage.value === 'error') return '演示中断'
  if (!demoStage.value) return ''
  return 'API 5xx 演示'
})
const demoStageText = computed(() => {
  switch (demoStage.value) {
    case 'fill':
      return '已填入 order-api 的 5xx 超时告警'
    case 'route':
      return '正在用告警标签和关键词匹配 SOP'
    case 'run':
      return '正在创建本次案件的 SOP run'
    case 'evidence':
      return '正在采集必查证据与观测云拨测等可选证据'
    case 'validate':
      return '正在提交 stepResults 并运行校验'
    case 'done':
      return '已生成 evidence、stepResults、validator 和案件轨迹'
    case 'error':
      return '请查看 toast 或重试演示'
    default:
      return ''
  }
})
const filteredSops = computed(() => {
  const kw = keyword.value.toLowerCase()
  if (!kw) return sops.value
  return sops.value.filter((sop) => [
    sop.name,
    sop.description,
    sop.domain,
    sop.scenario,
    sop.owner,
    ...sop.requiredEvidence,
    ...sop.optionalEvidence,
    ...sop.keywords,
  ].filter(Boolean).join(' ').toLowerCase().includes(kw))
})
const mvpRequiredEvidence = computed(() => {
  const fromSop = startResult.value?.sop?.requiredEvidence || []
  if (fromSop.length) return fromSop
  return routeResult.value?.selected?.requiredEvidence || []
})
const mvpEvidenceCoverage = computed(() => buildEvidenceCoverage(mvpRequiredEvidence.value, evidenceRecords.value))

onMounted(loadAll)
watch(workspaceId, () => loadAll())

async function loadAll() {
  loading.value = true
  try {
    const res: any = await troubleshootingApi.listSops()
    sops.value = res.data || []
    if (!selectedSop.value && sops.value.length) selectedSop.value = sops.value[0]
    await loadGuanceConnectorConfig(false)
    await loadQueryTemplates(false)
  } catch (e: any) {
    mcToast.error(e?.message || '加载 SOP 失败')
  } finally {
    loading.value = false
  }
}

async function loadGuanceConnectorConfig(showToast = true) {
  guanceConfigLoading.value = true
  try {
    const res: any = await troubleshootingApi.getGuanceConnectorConfig()
    guanceConfig.value = res.data || null
    guanceConfigForm.value = guanceConfigToForm(guanceConfig.value)
  } catch (e: any) {
    if (showToast) mcToast.error(e?.message || '加载观测云连接配置失败')
  } finally {
    guanceConfigLoading.value = false
  }
}

function seedGuanceConnectorConfig() {
  guanceConfigForm.value = {
    ...guanceConfigForm.value,
    enabled: true,
    baseUrl: 'http://df-openapi.prd.sangfor.com',
    syntheticsPath: '/api/v1/df/query_data_v1',
    metricsPath: '/api/v1/df/query_data_v1',
    tokenHeader: 'DF-API-KEY',
    tokenPrefix: '',
    syntheticsLimit: guanceConfigForm.value.syntheticsLimit || 20,
    metricsLimit: guanceConfigForm.value.metricsLimit || 50,
    maxResponseChars: guanceConfigForm.value.maxResponseChars || 4000,
  }
  mcToast.success('已填入观测云 OpenAPI 地址和拨测路径，请补充 API Key 后保存')
}

async function saveGuanceConnectorConfig() {
  const form = guanceConfigForm.value
  if (form.enabled && !form.baseUrl?.trim()) {
    mcToast.warning('启用观测云时 Base URL 不能为空')
    return
  }
  guanceConfigSaving.value = true
  queryTemplatePreview.value = null
  try {
    const payload = normalizeGuanceConnectorPayload(form)
    const res: any = await troubleshootingApi.saveGuanceConnectorConfig(payload)
    guanceConfig.value = res.data || null
    guanceConfigForm.value = guanceConfigToForm(guanceConfig.value)
    mcToast.success('观测云连接配置已保存')
  } catch (e: any) {
    mcToast.error(e?.message || '保存观测云连接配置失败')
  } finally {
    guanceConfigSaving.value = false
  }
}

async function loadQueryTemplates(showToast = true) {
  queryTemplatesLoading.value = true
  try {
    const res: any = await troubleshootingApi.listQueryTemplates({
      provider: 'guance',
      evidenceType: queryTemplateEvidenceType.value,
    })
    queryTemplates.value = res.data || []
    if (selectedQueryTemplateId.value && !queryTemplates.value.some((item) => sameId(item.id, selectedQueryTemplateId.value))) {
      selectedQueryTemplateId.value = null
    }
    if (!selectedQueryTemplateId.value && queryTemplates.value.length) {
      editQueryTemplate(queryTemplates.value[0])
    }
  } catch (e: any) {
    if (showToast) mcToast.error(e?.message || '加载观测云模板失败')
  } finally {
    queryTemplatesLoading.value = false
  }
}

async function onQueryTemplateEvidenceTypeChange() {
  selectedQueryTemplateId.value = null
  queryTemplatePreview.value = null
  queryTemplateForm.value = blankGuanceQueryTemplate(queryTemplateEvidenceType.value)
  await loadQueryTemplates(false)
}

function newQueryTemplate() {
  selectedQueryTemplateId.value = null
  queryTemplateForm.value = blankGuanceQueryTemplate(queryTemplateEvidenceType.value)
  queryTemplatePreview.value = null
}

function seedGuanceDialTemplate() {
  queryTemplateForm.value = blankGuanceQueryTemplate(queryTemplateEvidenceType.value)
  queryTemplatePreview.value = null
  mcToast.success('已填入观测云默认模板，确认后保存到数据库')
}

async function seedGuanceDefaultTemplates() {
  queryTemplateSeeding.value = true
  queryTemplatePreview.value = null
  try {
    const res: any = await troubleshootingApi.seedGuanceDefaultTemplates()
    const seeded = (res.data || []) as TroubleshootingQueryTemplate[]
    await loadQueryTemplates(false)
    const current = seeded.find((item) => item.evidenceType === queryTemplateEvidenceType.value)
    const refreshed = current
      ? queryTemplates.value.find((item) => sameId(item.id, current.id) || item.templateKey === current.templateKey)
      : queryTemplates.value[0]
    if (refreshed) editQueryTemplate(refreshed)
    mcToast.success(`已补齐 ${seeded.length} 个观测云默认模板`)
  } catch (e: any) {
    mcToast.error(e?.message || '初始化观测云默认模板失败')
  } finally {
    queryTemplateSeeding.value = false
  }
}

function editQueryTemplate(template: TroubleshootingQueryTemplate) {
  selectedQueryTemplateId.value = template.id
  queryTemplatePreview.value = null
  queryTemplateForm.value = {
    provider: template.provider || 'guance',
    evidenceType: template.evidenceType || 'synthetics',
    templateKey: template.templateKey || '',
    name: template.name || template.templateKey || '',
    description: template.description || '',
    payloadTemplate: template.payloadTemplate || '',
    dqlTemplate: template.dqlTemplate || '',
    matchJson: template.matchJson || '',
    enabled: template.enabled,
    defaultTemplate: template.defaultTemplate,
    priority: template.priority || 0,
  }
}

async function saveQueryTemplate() {
  const validationError = validateQueryTemplateForm()
  if (validationError) {
    mcToast.warning(validationError)
    return
  }
  queryTemplateSaving.value = true
  queryTemplatePreview.value = null
  try {
    const payload = normalizeQueryTemplatePayload(queryTemplateForm.value)
    const normalizedEvidenceType = payload.evidenceType as QueryTemplateEvidenceType
    if (queryTemplateEvidenceTypes.some((option) => option.value === normalizedEvidenceType)) {
      queryTemplateEvidenceType.value = normalizedEvidenceType
    }
    const res: any = selectedQueryTemplateId.value
      ? await troubleshootingApi.updateQueryTemplate(selectedQueryTemplateId.value, payload)
      : await troubleshootingApi.createQueryTemplate(payload)
    const saved = res.data as TroubleshootingQueryTemplate
    selectedQueryTemplateId.value = saved.id
    await loadQueryTemplates(false)
    const refreshed = queryTemplates.value.find((item) => sameId(item.id, saved?.id))
    if (refreshed) editQueryTemplate(refreshed)
    else if (saved?.id) selectedQueryTemplateId.value = saved.id
    mcToast.success('观测云查询模板已保存')
  } catch (e: any) {
    mcToast.error(e?.message || '保存观测云模板失败')
  } finally {
    queryTemplateSaving.value = false
  }
}

async function deleteQueryTemplate() {
  if (!selectedQueryTemplateId.value) return
  queryTemplateSaving.value = true
  try {
    await troubleshootingApi.deleteQueryTemplate(selectedQueryTemplateId.value)
    newQueryTemplate()
    await loadQueryTemplates(false)
    mcToast.success('观测云查询模板已删除')
  } catch (e: any) {
    mcToast.error(e?.message || '删除观测云模板失败')
  } finally {
    queryTemplateSaving.value = false
  }
}

function applyQueryTemplateToLabels() {
  if (!queryTemplateForm.value.templateKey?.trim()) {
    mcToast.warning('请先填写模板 Key')
    return
  }
  const labels = parseLabels()
  const templateKey = queryTemplateForm.value.templateKey.trim()
  const evidenceType = queryTemplateForm.value.evidenceType?.trim() || queryTemplateEvidenceType.value
  labels.payloadTemplateName = templateKey
  if (evidenceType === 'synthetics') labels.syntheticsPayloadTemplateName = templateKey
  if (evidenceType === 'host') labels.hostPayloadTemplateName = templateKey
  if (evidenceType === 'container') labels.containerPayloadTemplateName = templateKey
  if (evidenceType === 'k8s') labels.k8sPayloadTemplateName = templateKey
  if (evidenceType === 'metrics') labels.metricsPayloadTemplateName = templateKey
  if (evidenceType === 'synthetics' && !labels.syntheticsTaskName) {
    labels.syntheticsTaskName = previewForm.value.alertName || '马来-国际CPQ-首页'
  }
  labelsText.value = formatJson(labels)
  mcToast.success('已写入模板名，可直接创建 run 采集观测云证据')
}

async function previewQueryTemplate() {
  const validationError = validateQueryTemplateForm()
  if (validationError) {
    mcToast.warning(validationError)
    return
  }
  queryTemplatePreviewLoading.value = true
  queryTemplatePreview.value = null
  try {
    const res: any = await troubleshootingApi.previewQueryTemplate({
      template: normalizeQueryTemplatePayload(queryTemplateForm.value),
      alert: buildRoutePayload(),
    })
    queryTemplatePreview.value = res.data || null
    if (queryTemplatePreview.value?.status === 'collected') {
      mcToast.success('观测云试查完成')
    } else {
      mcToast.warning(queryTemplatePreview.value?.error || '观测云试查未采集到可用结果')
    }
  } catch (e: any) {
    mcToast.error(e?.message || '观测云试查失败')
  } finally {
    queryTemplatePreviewLoading.value = false
  }
}

async function previewRoute() {
  if (!demoRunning.value) demoStage.value = ''
  routing.value = true
  try {
    const res: any = await troubleshootingApi.previewRoute(buildRoutePayload())
    routeResult.value = res.data || null
  } catch (e: any) {
    mcToast.error(e?.message || 'SOP 匹配失败')
  } finally {
    routing.value = false
  }
}

async function loadRuns() {
  if (!caseId.value) return
  runsLoading.value = true
  try {
    const res: any = await troubleshootingApi.listCaseRuns(caseId.value)
    sopRuns.value = res.data || []
    if (selectedRunId.value && !sopRuns.value.some((run) => sameId(run.id, selectedRunId.value))) {
      selectedRunId.value = null
    }
    if (!selectedRunId.value && sopRuns.value.length) {
      selectedRunId.value = sopRuns.value[0].id
    }
    if (selectedRunId.value) {
      await loadEvidence(selectedRunId.value, false)
    } else {
      selectedRunEvidence.value = []
    }
  } catch (e: any) {
    mcToast.error(e?.message || '查询 SOP run 失败')
  } finally {
    runsLoading.value = false
  }
}

async function createMvpRun() {
  if (!demoRunning.value) demoStage.value = ''
  mvpLoading.value = true
  completeResult.value = null
  evidenceRecords.value = []
  selectedRunEvidence.value = []
  try {
    const id = ensureCaseId()
    const res: any = await troubleshootingApi.createCaseRun(id, buildRoutePayload())
    startResult.value = res.data || null
    routeResult.value = startResult.value?.route || routeResult.value
    resetMvpPayload()
    selectedRunId.value = startResult.value?.run.id || null
    await loadRuns()
    mcToast.success('排障 run 已创建')
  } catch (e: any) {
    mcToast.error(e?.message || '创建排障 run 失败')
  } finally {
    mvpLoading.value = false
  }
}

async function collectMvpEvidence() {
  if (!demoRunning.value) demoStage.value = ''
  if (!mvpRun.value) return
  collecting.value = true
  completeResult.value = null
  try {
    const res: any = await troubleshootingApi.collectEvidence(mvpRun.value.id, { includeOptional: true })
    const data = res.data || null
    applyEvidenceCollection(data)
    await loadRuns()
    selectedRunEvidence.value = evidenceRecords.value
    mcToast.success('证据已采集并写入 stepResults 草稿')
  } catch (e: any) {
    mcToast.error(e?.message || '采集证据失败')
  } finally {
    collecting.value = false
  }
}

async function completeMvpRun() {
  if (!demoRunning.value) demoStage.value = ''
  if (!mvpRun.value) return
  completing.value = true
  try {
    const stepResults = parseJsonValue(stepResultsText.value, [])
    const finalReport = parseJsonValue(finalReportText.value, {})
    if (!Array.isArray(stepResults)) {
      mcToast.warning('stepResults 必须是数组')
      return
    }
    const res: any = await troubleshootingApi.completeRun(mvpRun.value.id, {
      stepResults,
      finalReport,
    })
    completeResult.value = res.data || null
    if (completeResult.value?.run && startResult.value) {
      startResult.value = {
        ...startResult.value,
        run: completeResult.value.run,
      }
      selectedRunId.value = completeResult.value.run.id
    }
    await loadRuns()
    mcToast.success('排障 run 已完成校验')
  } catch (e: any) {
    mcToast.error(e?.message || '提交排障 run 失败')
  } finally {
    completing.value = false
  }
}

async function runApi5xxDemo() {
  if (demoRunning.value) return
  demoRunning.value = true
  demoStage.value = 'fill'
  completeResult.value = null
  startResult.value = null
  evidenceRecords.value = []
  selectedRunEvidence.value = []
  sopRuns.value = []
  selectedRunId.value = null
  seedApi5xxDemoAlert()

  try {
    demoStage.value = 'route'
    routing.value = true
    const routeRes: any = await troubleshootingApi.previewRoute(buildRoutePayload())
    routeResult.value = routeRes.data || null
    routing.value = false

    demoStage.value = 'run'
    mvpLoading.value = true
    const createRes: any = await troubleshootingApi.createCaseRun(caseId.value, buildRoutePayload())
    startResult.value = createRes.data || null
    if (!startResult.value?.run?.id) throw new Error('SOP run 创建失败')
    routeResult.value = startResult.value.route || routeResult.value
    resetMvpPayload()
    selectedRunId.value = startResult.value.run.id
    mvpLoading.value = false

    demoStage.value = 'evidence'
    collecting.value = true
    const collectRes: any = await troubleshootingApi.collectEvidence(startResult.value.run.id, { includeOptional: true })
    applyEvidenceCollection(collectRes.data || null)
    collecting.value = false

    demoStage.value = 'validate'
    completing.value = true
    const stepResults = parseJsonValue(stepResultsText.value, [])
    const finalReport = parseJsonValue(finalReportText.value, {})
    if (!Array.isArray(stepResults)) throw new Error('stepResults 必须是数组')
    const completeRes: any = await troubleshootingApi.completeRun(startResult.value.run.id, {
      stepResults,
      finalReport,
    })
    completeResult.value = completeRes.data || null
    if (completeResult.value?.run && startResult.value) {
      startResult.value = {
        ...startResult.value,
        run: completeResult.value.run,
      }
      selectedRunId.value = completeResult.value.run.id
    }
    completing.value = false

    await loadRuns()
    if (evidenceRecords.value.length) selectedRunEvidence.value = evidenceRecords.value
    demoStage.value = 'done'
    mcToast.success('API 5xx 演示完成')
  } catch (e: any) {
    demoStage.value = 'error'
    mcToast.error(e?.message || 'API 5xx 演示失败')
  } finally {
    routing.value = false
    mvpLoading.value = false
    collecting.value = false
    completing.value = false
    demoRunning.value = false
  }
}

function applyEvidenceCollection(data: any) {
  evidenceRecords.value = data?.evidenceRecords || []
  selectedRunEvidence.value = evidenceRecords.value
  if (data?.stepResults) stepResultsText.value = formatJson(data.stepResults)
  if (data?.finalReportTemplate) finalReportText.value = formatJson(data.finalReportTemplate)
  if (data?.run && startResult.value) {
    startResult.value = {
      ...startResult.value,
      run: data.run,
    }
    selectedRunId.value = data.run.id
  }
}

function seedApi5xxDemoAlert() {
  caseId.value = `case-demo-api-5xx-${timestamp()}`
  previewForm.value = {
    eventId: `evt-demo-api-5xx-${timestamp()}`,
    source: 'wecom',
    severity: 'P1',
    alertName: 'API 5xx error rate high',
    status: 'firing',
    serviceName: 'order-api',
    env: 'prod',
    cluster: 'bwx-prod-k8s',
    namespace: 'default',
    pod: 'order-api-7d6c',
    instance: '10.0.0.12',
    endpoint: '/api/orders',
    metricName: 'http_5xx_rate',
    message: 'P1 order-api HTTP 503 timeout after deployment, 5xx rate elevated and latency p95 increased.',
    rawText: 'P1 order-api HTTP 503 timeout after deployment',
    topK: 3,
  }
  labelsText.value = formatJson({
    namespace: 'default',
    endpoint: '/api/orders',
    statusCode: '503',
    serviceName: 'order-api',
    env: 'prod',
    cluster: 'bwx-prod-k8s',
    syntheticsTaskName: 'order-api 首页拨测',
    hostName: 'order-api-node-01',
    podName: 'order-api-7d6c',
    containerName: 'order-api',
  })
}

function resetMvpPayload() {
  if (!startResult.value) return
  stepResultsText.value = formatJson(startResult.value.sampleStepResults || [])
  finalReportText.value = formatJson(startResult.value.finalReportTemplate || {})
}

async function loadEvidence(runId: string | number, showError = true) {
  evidenceLoading.value = true
  try {
    const res: any = await troubleshootingApi.listEvidence(runId)
    selectedRunEvidence.value = res.data || []
  } catch (e: any) {
    if (showError) mcToast.error(e?.message || '加载 evidence 失败')
    selectedRunEvidence.value = []
  } finally {
    evidenceLoading.value = false
  }
}

function buildRoutePayload(): TroubleshootingRouteRequest {
  return {
    ...previewForm.value,
    labels: parseLabels(),
  }
}

function blankGuanceQueryTemplate(evidenceType: QueryTemplateEvidenceType = 'synthetics'): TroubleshootingQueryTemplateRequest {
  const key = defaultTemplateKey(evidenceType)
  return {
    provider: 'guance',
    evidenceType,
    templateKey: key,
    name: defaultTemplateName(evidenceType),
    description: defaultTemplateDescription(evidenceType),
    payloadTemplate: defaultGuancePayloadTemplate,
    dqlTemplate: defaultGuanceDqlTemplates[evidenceType],
    matchJson: defaultGuanceMatchJsonTemplates[evidenceType],
    enabled: true,
    defaultTemplate: evidenceType === 'synthetics',
    priority: evidenceType === 'synthetics' ? 100 : 80,
  }
}

function defaultTemplateKey(evidenceType: QueryTemplateEvidenceType) {
  switch (evidenceType) {
    case 'host':
      return 'guance-host-by-name'
    case 'container':
      return 'guance-container-by-pod'
    case 'k8s':
      return 'guance-k8s-by-namespace'
    case 'metrics':
      return 'guance-metrics-by-service'
    default:
      return 'guance-http-dial-by-name'
  }
}

function defaultTemplateName(evidenceType: QueryTemplateEvidenceType) {
  switch (evidenceType) {
    case 'host':
      return '观测云基础设施主机 - 按主机名'
    case 'container':
      return '观测云基础设施容器 - 按 Pod'
    case 'k8s':
      return '观测云 K8s 事件 - 按命名空间'
    case 'metrics':
      return '观测云指标查询 - 按服务'
    default:
      return '观测云 HTTP 拨测 - 按任务名'
  }
}

function defaultTemplateDescription(evidenceType: QueryTemplateEvidenceType) {
  switch (evidenceType) {
    case 'host':
      return '适用于观测云「基础设施 > 主机」的 DQL 查询，按 hostName/host 标签匹配。'
    case 'container':
      return '适用于观测云「基础设施 > 容器」的 DQL 查询，按 pod/container 标签匹配。'
    case 'k8s':
      return '适用于观测云 K8s/容器事件查询，按 namespace/cluster 标签匹配。'
    case 'metrics':
      return '适用于观测云指标 DQL 查询，按服务、指标或告警关键词匹配。'
    default:
      return '适用于观测云「可用性检测 > 任务」的 http_dial_testing 查询。'
  }
}

function blankGuanceConnectorConfig(): TroubleshootingConnectorConfigRequest {
  return {
    enabled: false,
    baseUrl: '',
    syntheticsPath: '/api/v1/df/query_data_v1',
    metricsPath: '/api/v1/df/query_data_v1',
    token: '',
    tokenHeader: 'DF-API-KEY',
    tokenPrefix: '',
    window: 'alert_time +/- 15m',
    syntheticsLimit: 20,
    metricsWindow: 'alert_time +/- 15m',
    metricsLimit: 50,
    maxResponseChars: 4000,
  }
}

function guanceConfigToForm(config: TroubleshootingConnectorConfig | null): TroubleshootingConnectorConfigRequest {
  if (!config) return blankGuanceConnectorConfig()
  return {
    enabled: config.enabled,
    baseUrl: config.baseUrl || '',
    syntheticsPath: config.syntheticsPath || '/api/v1/df/query_data_v1',
    metricsPath: config.metricsPath || '/api/v1/df/query_data_v1',
    token: '',
    tokenHeader: config.tokenHeader || 'DF-API-KEY',
    tokenPrefix: config.tokenPrefix ?? '',
    window: config.window || 'alert_time +/- 15m',
    syntheticsLimit: config.syntheticsLimit || 20,
    metricsWindow: config.metricsWindow || 'alert_time +/- 15m',
    metricsLimit: config.metricsLimit || 50,
    maxResponseChars: config.maxResponseChars || 4000,
  }
}

function normalizeGuanceConnectorPayload(form: TroubleshootingConnectorConfigRequest): TroubleshootingConnectorConfigRequest {
  return {
    enabled: form.enabled === true,
    baseUrl: form.baseUrl?.trim() || '',
    syntheticsPath: form.syntheticsPath?.trim() || '/api/v1/df/query_data_v1',
    metricsPath: form.metricsPath?.trim() || '/api/v1/df/query_data_v1',
    token: form.token?.trim() || '',
    tokenHeader: form.tokenHeader?.trim() || 'DF-API-KEY',
    tokenPrefix: form.tokenPrefix ?? '',
    window: form.window?.trim() || 'alert_time +/- 15m',
    syntheticsLimit: Number.isFinite(Number(form.syntheticsLimit)) ? Number(form.syntheticsLimit) : 20,
    metricsWindow: form.metricsWindow?.trim() || 'alert_time +/- 15m',
    metricsLimit: Number.isFinite(Number(form.metricsLimit)) ? Number(form.metricsLimit) : 50,
    maxResponseChars: Number.isFinite(Number(form.maxResponseChars)) ? Number(form.maxResponseChars) : 4000,
  }
}

function validateQueryTemplateForm() {
  const form = queryTemplateForm.value
  if (!form.templateKey?.trim()) return '模板 Key 不能为空'
  if (!form.name?.trim()) return '模板名称不能为空'
  if (!form.provider?.trim()) return 'Provider 不能为空'
  if (!form.evidenceType?.trim()) return '证据类型不能为空'
  if (!form.payloadTemplate?.trim()) return 'Payload 模板不能为空'
  try {
    JSON.parse(renderJsonTemplateForValidation(form.payloadTemplate))
  } catch {
    return 'Payload 模板需要是合法 JSON；占位符可以放在字符串内，或作为完整 JSON 值'
  }
  if (form.matchJson?.trim()) {
    try {
      JSON.parse(form.matchJson)
    } catch {
      return 'matchJson 需要是合法 JSON'
    }
  }
  return ''
}

function renderJsonTemplateForValidation(template: string) {
  return template.replace(/\$\{[A-Za-z0-9_.-]+}/g, (placeholder, offset) =>
    isInsideJsonString(template, offset) ? '' : '""'
  )
}

function isInsideJsonString(template: string, position: number) {
  let inString = false
  let escaped = false
  for (let i = 0; i < position; i += 1) {
    const ch = template[i]
    if (escaped) {
      escaped = false
      continue
    }
    if (ch === '\\') {
      escaped = true
      continue
    }
    if (ch === '"') inString = !inString
  }
  return inString
}

function normalizeQueryTemplatePayload(form: TroubleshootingQueryTemplateRequest): TroubleshootingQueryTemplateRequest {
  return {
    provider: form.provider.trim(),
    evidenceType: form.evidenceType.trim(),
    templateKey: form.templateKey.trim(),
    name: form.name.trim(),
    description: form.description?.trim() || '',
    payloadTemplate: form.payloadTemplate.trim(),
    dqlTemplate: form.dqlTemplate?.trim() || '',
    matchJson: form.matchJson?.trim() || '',
    enabled: form.enabled !== false,
    defaultTemplate: form.defaultTemplate === true,
    priority: Number.isFinite(Number(form.priority)) ? Number(form.priority) : 0,
  }
}

function ensureCaseId() {
  if (!caseId.value) {
    caseId.value = `case-mvp-${timestamp()}`
  }
  return caseId.value
}

function timestamp() {
  return new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
}

function parseLabels(): Record<string, unknown> {
  if (!labelsText.value.trim()) return {}
  try {
    return JSON.parse(labelsText.value)
  } catch {
    mcToast.warning('Labels JSON 格式不正确，已按空 labels 预览')
    return {}
  }
}

function parseJsonValue<T>(text: string, fallback: T): T {
  if (!text.trim()) return fallback
  return JSON.parse(text)
}

function safeParseJson<T>(text: string | undefined, fallback: T): T {
  if (!text?.trim()) return fallback
  try {
    return JSON.parse(text)
  } catch {
    return fallback
  }
}

async function toggleRun(run: TroubleshootingSopRun) {
  if (isSelectedRun(run)) {
    selectedRunId.value = null
    selectedRunEvidence.value = []
    return
  }
  selectedRunId.value = run.id
  await loadEvidence(run.id)
}

function isSelectedRun(run: TroubleshootingSopRun) {
  return sameId(run.id, selectedRunId.value)
}

function sameId(a: string | number | null | undefined, b: string | number | null | undefined) {
  return a != null && b != null && String(a) === String(b)
}

function demoStepClass(key: DemoStepKey) {
  const current = demoSteps.findIndex((step) => step.key === demoStage.value)
  const own = demoSteps.findIndex((step) => step.key === key)
  return {
    active: demoStage.value === key,
    done: demoStage.value === 'done' || (current > own && own >= 0),
  }
}

function runEvidenceCoverage(run: TroubleshootingSopRun): EvidenceCoverageItem[] {
  if (!isSelectedRun(run)) return []
  return buildEvidenceCoverage(requiredEvidenceForRun(run), selectedRunEvidence.value)
}

function requiredEvidenceForRun(run: TroubleshootingSopRun): string[] {
  const matchedSop = sops.value.find((sop) =>
    sameId(sop.skillId, run.sopSkillId) || (sop.domain === run.domain && sop.scenario === run.scenario)
  )
  if (matchedSop?.requiredEvidence?.length) return matchedSop.requiredEvidence
  return unique(parseRunSteps(run).flatMap((step) => step.evidenceTypes || []))
}

function buildEvidenceCoverage(requiredEvidence: string[], records: TroubleshootingEvidenceRecord[]): EvidenceCoverageItem[] {
  const types = unique([
    ...requiredEvidence,
    ...records.map((record) => record.evidenceType),
  ])
  return types
    .filter((type) => type && type.trim())
    .map((type) => coverageItem(type, records.filter((record) => sameEvidenceType(record.evidenceType, type))))
}

function coverageItem(type: string, records: TroubleshootingEvidenceRecord[]): EvidenceCoverageItem {
  const evidenceIds = records.map((record) => record.evidenceId).filter(Boolean)
  if (!records.length) {
    return {
      type,
      label: '待采集',
      tone: 'neutral',
      evidenceIds,
      detail: '该必查证据尚未采集，当前不能用它支撑根因判断。',
    }
  }

  const collected = records.filter((record) => 'collected'.toLowerCase() === normalize(record.status))
  const unavailable = records.filter((record) => ['unavailable', 'failed'].includes(normalize(record.status)))
  const realCollected = collected.filter((record) => normalize(record.source) !== 'mock-troubleshooting')
  const mockCollected = collected.filter((record) => normalize(record.source) === 'mock-troubleshooting')

  if (realCollected.length) {
    return {
      type,
      label: unavailable.length ? '部分可用' : '已采集',
      tone: unavailable.length ? 'info' : 'ok',
      evidenceIds,
      detail: `真实来源：${sourceList(realCollected)}。${unavailable.length ? '仍有部分连接器不可用，结论需标注缺口。' : '可作为 SOP 判断依据。'}`,
    }
  }

  if (mockCollected.length) {
    return {
      type,
      label: '模拟证据',
      tone: 'info',
      evidenceIds,
      detail: '当前来自 mock-troubleshooting，只能验证流程，不能当作真实根因证据。',
    }
  }

  if (unavailable.length) {
    return {
      type,
      label: '不可用',
      tone: 'warn',
      evidenceIds,
      detail: compactText(unavailable[0]?.summary || evidenceError(unavailable[0]) || '连接器不可用，已记录证据缺口。', 180),
    }
  }

  return {
    type,
    label: records[0]?.status || '未知',
    tone: 'warn',
    evidenceIds,
    detail: compactText(records[0]?.summary || '证据状态异常，需要人工复核。', 180),
  }
}

function evidenceCoverageSummary(items: EvidenceCoverageItem[]) {
  if (!items.length) return '暂无证据要求'
  const ok = items.filter((item) => item.tone === 'ok').length
  const mock = items.filter((item) => item.label === '模拟证据').length
  const gaps = items.filter((item) => item.tone === 'warn' || item.tone === 'neutral').length
  return `真实 ${ok} / 模拟 ${mock} / 缺口 ${gaps}`
}

function parseRunSteps(run: TroubleshootingSopRun): TroubleshootingSopStepResult[] {
  const parsed = safeParseJson<unknown>(run.stepResultsJson, [])
  return Array.isArray(parsed) ? parsed as TroubleshootingSopStepResult[] : []
}

function parseRunValidation(run: TroubleshootingSopRun): TroubleshootingSopValidationResult | null {
  const parsed = safeParseJson<Partial<TroubleshootingSopValidationResult> | null>(run.validationErrorsJson, null)
  if (!parsed || typeof parsed.valid !== 'boolean') return null
  return {
    valid: parsed.valid,
    missingEvidence: Array.isArray(parsed.missingEvidence) ? parsed.missingEvidence : [],
    errors: Array.isArray(parsed.errors) ? parsed.errors : [],
  }
}

function runGroupReport(run: TroubleshootingSopRun) {
  const parsed = safeParseJson<Record<string, unknown>>(run.finalReportJson, {})
  const value = parsed.groupReport
  return typeof value === 'string' ? value : ''
}

function unique(values: string[]): string[] {
  return [...new Set(values.map((value) => normalize(value)).filter(Boolean))]
}

function normalize(value: unknown) {
  return String(value ?? '').trim().toLowerCase()
}

function sameEvidenceType(a: string, b: string) {
  return normalize(a) === normalize(b)
}

function sourceList(records: TroubleshootingEvidenceRecord[]) {
  return unique(records.map((record) => record.source)).join(', ') || '-'
}

function evidenceError(evidence?: TroubleshootingEvidenceRecord) {
  if (!evidence) return ''
  const content = evidenceContent(evidence)
  return typeof content.error === 'string' ? content.error : ''
}

function evidenceContent(evidence: TroubleshootingEvidenceRecord): Record<string, unknown> {
  return safeParseJson<Record<string, unknown>>(evidence.contentJson, {})
}

function evidenceNormalized(evidence: TroubleshootingEvidenceRecord): Record<string, unknown> {
  const content = evidenceContent(evidence)
  const normalized = content.normalized
  return normalized && typeof normalized === 'object' && !Array.isArray(normalized)
    ? normalized as Record<string, unknown>
    : {}
}

function evidenceHighlights(evidence: TroubleshootingEvidenceRecord): string[] {
  const normalized = evidenceNormalized(evidence)
  const highlights: string[] = []
  if (normalized.matchedCount != null) highlights.push(`命中 ${normalized.matchedCount}`)
  if (normalized.changeCount != null) highlights.push(`变更 ${normalized.changeCount}`)
  if (normalized.recordCount != null) highlights.push(`记录 ${normalized.recordCount}`)
  if (normalized.checkCount != null && normalized.failedCount != null) {
    highlights.push(`拨测失败 ${normalized.failedCount}/${normalized.checkCount}`)
  }
  if (normalized.failureRate != null) highlights.push(`失败率 ${normalized.failureRate}%`)
  const abnormalCount = Number(normalized.abnormalCount)
  if (Number.isFinite(abnormalCount) && abnormalCount > 0) highlights.push(`异常 ${abnormalCount}`)
  const resourcePressure = toDisplayArray(normalized.resourcePressure)
  if (resourcePressure.length) highlights.push(`资源 ${compactText(resourcePressure[0], 42)}`)
  const restartObjects = toDisplayArray(normalized.restartObjects)
  if (restartObjects.length) highlights.push(`重启 ${restartObjects.length}`)
  const failedStatusCodes = toDisplayArray(normalized.failedStatusCodes)
  if (failedStatusCodes.length) highlights.push(`状态码 ${failedStatusCodes.slice(0, 3).join(', ')}`)
  const affectedRegions = toDisplayArray(normalized.affectedRegions)
  if (affectedRegions.length) highlights.push(`区域 ${compactText(affectedRegions.slice(0, 2).join(', '), 42)}`)
  if (typeof normalized.rollbackAvailable === 'boolean') {
    highlights.push(normalized.rollbackAvailable ? '可回滚' : '未发现回滚信息')
  }
  const signatures = toStringArray(normalized.errorSignatures)
  if (signatures.length) highlights.push(`签名 ${compactText(signatures[0], 42)}`)
  return highlights.slice(0, 4)
}

function evidencePreviewLines(evidence: TroubleshootingEvidenceRecord): string[] {
  const content = evidenceContent(evidence)
  const normalized = evidenceNormalized(evidence)
  const availabilityConclusion = normalized.availabilityConclusion
  if (typeof availabilityConclusion === 'string' && availabilityConclusion) {
    const signals = toDisplayArray(normalized.diagnosisSignals)
    return [
      compactText(availabilityConclusion, 160),
      ...signals.slice(0, 2).map((item) => compactText(item, 140)),
    ]
  }

  const infrastructureConclusion = normalized.infrastructureConclusion
  if (typeof infrastructureConclusion === 'string' && infrastructureConclusion) {
    const signals = toDisplayArray(normalized.infrastructureSignals)
    return [
      compactText(infrastructureConclusion, 160),
      ...signals.slice(0, 2).map((item) => compactText(item, 140)),
    ]
  }

  const messages = toStringArray(normalized.topMessages)
  if (messages.length) return messages.slice(0, 3).map((item) => compactText(item, 140))

  if (Array.isArray(normalized.changes)) {
    return normalized.changes
      .slice(0, 3)
      .map(changeLine)
      .filter(Boolean)
  }

  const textPreview = normalized.textPreview
  if (typeof textPreview === 'string' && textPreview) return [compactText(textPreview, 160)]

  const rawPreview = content.rawPreview || content.responsePreview || content.error
  return typeof rawPreview === 'string' && rawPreview
    ? [compactText(rawPreview, 160)]
    : []
}

function changeLine(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  const row = value as Record<string, unknown>
  return [
    row.changeType || row.type,
    row.serviceName || row.app,
    row.version || row.image || row.commitId,
    row.status,
    row.operator || row.owner,
  ].filter((item) => item != null && String(item).trim())
    .map((item) => compactText(String(item), 60))
    .join(' · ')
}

function toStringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : []
}

function toDisplayArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.map((item) => String(item)).filter((item) => item.trim().length > 0)
    : []
}

function compactText(value: string, max = 120) {
  return value.length <= max ? value : `${value.slice(0, max)}...`
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function percent(value?: number) {
  if (value == null) return '-'
  return `${Math.round(value * 100)}%`
}
</script>

<style scoped>
.troubleshooting-shell {
  padding: 28px;
}

.troubleshooting-frame {
  min-height: 100%;
}

.troubleshooting-page {
  display: grid;
  gap: 18px;
  align-content: start;
  min-width: 0;
}

.compact {
  margin-bottom: 0;
  flex-wrap: wrap;
}

.compact > div:first-child {
  min-width: 280px;
  flex: 1 1 460px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.compact-select {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  min-width: 0;
}

.compact-select select {
  min-height: 30px;
  border: 1px solid var(--mc-border-light);
  border-radius: 6px;
  padding: 0 28px 0 10px;
  background: var(--mc-bg-primary);
  color: var(--mc-text-primary);
  font-size: 13px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  white-space: nowrap;
  min-width: 0;
}

.icon-btn svg {
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
}

.sop-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  overflow: hidden;
  background: var(--mc-border-light);
  min-height: 72px;
}

.sop-metrics > div {
  background: var(--mc-bg);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-value {
  font-size: 22px;
  line-height: 1.15;
  font-weight: 700;
  color: var(--mc-text-primary);
}

.metric-label,
.muted {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 0.8fr);
  gap: 18px;
  align-items: start;
  min-width: 0;
}

.sop-section {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 14px;
  min-width: 0;
  background: var(--mc-bg);
}

.sop-list-section,
.route-section,
.mvp-section,
.run-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 0;
  min-width: 0;
  flex-wrap: wrap;
}

.section-title {
  min-width: 0;
}

.section-title p {
  margin: 4px 0 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.section-head h2 {
  margin: 0;
  font-size: 15px;
  line-height: 1.3;
}

.search-input,
.case-search input,
.route-form input,
.route-form textarea,
.connector-form-grid input,
.template-form-grid input,
.template-form-grid textarea {
  width: 100%;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 8px 10px;
  font: inherit;
  min-height: 34px;
  min-width: 0;
}

.guance-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.guance-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.72fr) minmax(0, 1.28fr);
  gap: 12px;
  min-width: 0;
  align-items: start;
}

.guance-connector-card {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  background: var(--mc-bg);
}

.connector-form-grid {
  display: grid;
  grid-template-columns: 0.75fr repeat(3, minmax(0, 1fr));
  gap: 10px;
  min-width: 0;
}

.connector-form-grid label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  min-width: 0;
}

.template-list {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  overflow: hidden;
  min-width: 0;
}

.template-row {
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--mc-border-light);
  background: var(--mc-bg);
  color: inherit;
  padding: 10px 12px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
  text-align: left;
  min-width: 0;
}

.template-row:last-child {
  border-bottom: 0;
}

.template-row:hover,
.template-row.selected {
  background: var(--mc-primary-bg);
}

.template-row-main,
.template-row-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.template-row-main strong,
.template-row-main span,
.template-row-meta span:last-child {
  overflow-wrap: anywhere;
}

.template-row-main strong {
  color: var(--mc-text-primary);
  font-size: 13px;
}

.template-row-main span,
.template-row-meta span:last-child {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.template-row-meta {
  align-items: flex-end;
  flex: 0 0 auto;
  max-width: 42%;
}

.template-editor {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.template-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  min-width: 0;
}

.template-form-grid label,
.template-switches label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  min-width: 0;
}

.template-form-grid textarea {
  resize: vertical;
  font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.template-switches {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  min-width: 0;
}

.template-switches label {
  flex-direction: row;
  align-items: center;
}

.template-switches input {
  width: auto;
  min-height: 0;
}

.template-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.template-preview-panel {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  background: var(--mc-bg-muted);
}

.template-preview-panel.warn {
  border-color: rgba(148, 98, 0, 0.28);
  background: rgba(148, 98, 0, 0.08);
}

.template-preview-panel p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.icon-btn.danger {
  color: #a33a2a;
}

.search-input {
  width: min(260px, 100%);
  flex: 0 1 260px;
}

.sop-table-wrap {
  overflow: auto;
  max-height: min(56vh, 520px);
  min-width: 0;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
}

.sop-table {
  width: 100%;
  min-width: 680px;
  border-collapse: collapse;
  font-size: 13px;
  table-layout: fixed;
}

.sop-table th:nth-child(1),
.sop-table td:nth-child(1) {
  width: 24%;
}

.sop-table th:nth-child(2),
.sop-table td:nth-child(2) {
  width: 26%;
}

.sop-table th:nth-child(3),
.sop-table td:nth-child(3) {
  width: 30%;
}

.sop-table th:nth-child(4),
.sop-table td:nth-child(4) {
  width: 12%;
}

.sop-table th:nth-child(5),
.sop-table td:nth-child(5) {
  width: 8%;
}

.sop-table th,
.sop-table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid var(--mc-border-light);
  vertical-align: top;
}

.sop-table th {
  color: var(--mc-text-secondary);
  font-weight: 600;
  background: var(--mc-bg-muted);
  position: sticky;
  top: 0;
  z-index: 1;
}

.sop-table tbody tr {
  cursor: pointer;
}

.sop-table tbody tr:hover,
.sop-table tbody tr.selected {
  background: var(--mc-primary-bg);
}

.sop-table strong,
.sop-table .muted {
  display: block;
  overflow-wrap: anywhere;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.chip,
.status-pill,
.confidence,
.score {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  max-width: 100%;
  overflow-wrap: anywhere;
}

.chip.required {
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.status-pill {
  color: #20744a;
  background: rgba(32, 116, 74, 0.1);
}

.status-pill.warn,
.result-summary.fallback {
  color: #946200;
  background: rgba(148, 98, 0, 0.1);
}

.status-pill.info {
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.status-pill.neutral {
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
}

.route-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.route-form label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.span-2 {
  grid-column: 1 / -1;
}

.route-result {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.result-summary {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.result-label {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.candidate-list,
.run-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.candidate-row {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.run-row {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  cursor: pointer;
  min-width: 0;
}

.run-row.selected {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.run-summary {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.candidate-row div,
.run-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.candidate-row span,
.run-main span {
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.case-search {
  display: flex;
  gap: 8px;
  flex: 1 1 520px;
  justify-content: flex-end;
  flex-wrap: wrap;
  min-width: 0;
  max-width: 640px;
}

.case-search input {
  flex: 1 1 240px;
}

.demo-strip {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-muted);
  padding: 10px 12px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.demo-strip.done {
  border-color: rgba(32, 116, 74, 0.22);
  background: rgba(32, 116, 74, 0.08);
}

.demo-strip.warn {
  border-color: rgba(148, 98, 0, 0.22);
  background: rgba(148, 98, 0, 0.08);
}

.demo-status {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.demo-status strong {
  color: var(--mc-text-primary);
  font-size: 13px;
}

.demo-status span {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.demo-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  justify-content: flex-end;
  min-width: 0;
}

.demo-step {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg);
  border: 1px solid var(--mc-border-light);
}

.demo-step.active {
  color: var(--mc-primary);
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.demo-step.done {
  color: #20744a;
  border-color: rgba(32, 116, 74, 0.22);
  background: rgba(32, 116, 74, 0.1);
}

.run-meta {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  min-width: 160px;
  text-align: right;
}

.run-detail {
  border-top: 1px solid var(--mc-border-light);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  cursor: default;
}

.run-detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.run-detail-grid span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.step-timeline,
.evidence-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 8px;
  min-width: 0;
}

.step-card,
.evidence-card {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--mc-bg);
  min-width: 0;
}

.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.step-head strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.decision-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  margin-left: auto;
}

.step-card p,
.evidence-card p {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.evidence-highlights {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.evidence-highlight {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.evidence-preview {
  margin: 0;
  padding: 8px 0 0 16px;
  border-top: 1px solid var(--mc-border-light);
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.evidence-preview li + li {
  margin-top: 4px;
}

.evidence-gap-panel,
.evidence-gap-card {
  min-width: 0;
}

.evidence-gap-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px;
  min-width: 0;
}

.evidence-gap-item {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 10px;
  background: var(--mc-bg);
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.evidence-gap-item.ok {
  border-color: rgba(32, 116, 74, 0.24);
  background: rgba(32, 116, 74, 0.06);
}

.evidence-gap-item.info {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.evidence-gap-item.warn {
  border-color: rgba(148, 98, 0, 0.22);
  background: rgba(148, 98, 0, 0.08);
}

.evidence-gap-item.neutral {
  background: var(--mc-bg-muted);
}

.gap-item-head,
.compact-gap-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.gap-item-head strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.evidence-gap-item p {
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.empty-cell {
  color: var(--mc-text-secondary);
  text-align: center;
  padding: 28px 12px;
}

.compact-empty {
  padding: 14px 12px;
}

.mvp-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  min-width: 0;
}

.mvp-card {
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.prompt-card,
.report-card {
  grid-column: 1 / -1;
}

.mvp-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  min-width: 0;
  flex-wrap: wrap;
}

.mvp-card-head strong {
  color: var(--mc-text-primary);
  font-size: 14px;
  margin-right: auto;
  min-width: 0;
  overflow-wrap: anywhere;
}

.mvp-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.mvp-meta span,
.evidence-card > .muted {
  min-width: 0;
  overflow-wrap: anywhere;
}

.prompt-box,
.json-editor {
  width: 100%;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  padding: 10px;
  resize: vertical;
  min-width: 0;
}

.prompt-box {
  min-height: 220px;
}

.json-editor {
  min-height: 180px;
}

.text-button {
  border: 0;
  background: transparent;
  color: var(--mc-primary);
  padding: 0;
  cursor: pointer;
  font: inherit;
}

.validation-list {
  color: #946200;
  background: rgba(148, 98, 0, 0.1);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
}

.report-box {
  margin: 0;
  white-space: pre-wrap;
  border: 1px solid var(--mc-border-light);
  border-radius: 6px;
  padding: 10px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-muted);
  font: 13px/1.55 inherit;
  overflow-wrap: anywhere;
  overflow-x: auto;
}

@media (max-width: 1180px) {
  .workspace-grid,
  .guance-grid {
    grid-template-columns: 1fr;
  }

  .connector-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .sop-table-wrap {
    max-height: 440px;
  }
}

@media (max-width: 760px) {
  .troubleshooting-shell {
    padding: 14px;
  }

  .troubleshooting-page {
    gap: 14px;
    padding: 18px;
  }

  .compact > div:first-child {
    min-width: 0;
  }

  .sop-metrics {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .section-head,
  .demo-strip,
  .case-search {
    align-items: stretch;
    flex-direction: column;
  }

  .header-actions,
  .demo-steps {
    flex-direction: column;
    align-items: stretch;
    justify-content: stretch;
  }

  .header-actions .icon-btn,
  .header-actions .compact-select,
  .header-actions .compact-select select,
  .case-search .icon-btn {
    width: 100%;
  }

  .search-input {
    width: 100%;
    max-width: none;
  }

  .sop-table-wrap {
    max-height: 420px;
  }

  .route-form {
    grid-template-columns: 1fr;
  }

  .mvp-grid,
  .template-form-grid {
    grid-template-columns: 1fr;
  }

  .connector-form-grid {
    grid-template-columns: 1fr;
  }

  .template-row,
  .template-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .template-row-meta {
    align-items: flex-start;
    max-width: none;
  }

  .run-summary,
  .run-meta {
    align-items: stretch;
    flex-direction: column;
    text-align: left;
    min-width: 0;
  }

  .run-detail-grid {
    grid-template-columns: 1fr;
  }

  .step-timeline,
  .evidence-list {
    grid-template-columns: 1fr;
  }
}
</style>
