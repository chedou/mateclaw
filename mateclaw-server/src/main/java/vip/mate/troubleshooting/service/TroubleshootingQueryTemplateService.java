package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplateRequest;
import vip.mate.troubleshooting.model.TroubleshootingQueryTemplateEntity;
import vip.mate.troubleshooting.repository.TroubleshootingQueryTemplateMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class TroubleshootingQueryTemplateService {

    private static final String DEFAULT_PROVIDER = "guance";
    private static final String DEFAULT_EVIDENCE_TYPE = "synthetics";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Za-z0-9_.-]+}");
    private static final String GUANCE_DQL_PAYLOAD_TEMPLATE = """
            {
              "queries": [
                {
                  "qtype": "dql",
                  "query": {
                    "q": "${dqlQuery}",
                    "_funcList": [],
                    "funcList": [],
                    "maxPointCount": 720,
                    "interval": 10,
                    "align_time": true,
                    "sorder_by": [],
                    "slimit": ${limit},
                    "disable_sampling": false,
                    "timeRange": [],
                    "tz": "Asia/Shanghai"
                  }
                }
              ]
            }
            """;

    private final TroubleshootingQueryTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    public List<TroubleshootingQueryTemplateEntity> list(long workspaceId, String provider, String evidenceType) {
        LambdaQueryWrapper<TroubleshootingQueryTemplateEntity> wrapper =
                new LambdaQueryWrapper<TroubleshootingQueryTemplateEntity>()
                        .eq(TroubleshootingQueryTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingQueryTemplateEntity::getDeleted, 0);
        if (provider != null && !provider.isBlank()) {
            wrapper.eq(TroubleshootingQueryTemplateEntity::getProvider, normalize(provider));
        }
        if (evidenceType != null && !evidenceType.isBlank()) {
            wrapper.eq(TroubleshootingQueryTemplateEntity::getEvidenceType, normalize(evidenceType));
        }
        return templateMapper.selectList(wrapper
                .orderByDesc(TroubleshootingQueryTemplateEntity::getDefaultTemplate)
                .orderByDesc(TroubleshootingQueryTemplateEntity::getPriority)
                .orderByAsc(TroubleshootingQueryTemplateEntity::getTemplateKey));
    }

    public Optional<TroubleshootingQueryTemplateEntity> resolve(long workspaceId,
                                                               String provider,
                                                               String evidenceType,
                                                               String templateKey) {
        String normalizedProvider = normalize(value(provider, DEFAULT_PROVIDER));
        String normalizedEvidenceType = normalize(value(evidenceType, DEFAULT_EVIDENCE_TYPE));
        if (templateKey != null && !templateKey.isBlank()) {
            TroubleshootingQueryTemplateEntity byKey = templateMapper.selectOne(
                    baseLookup(workspaceId, normalizedProvider, normalizedEvidenceType)
                            .eq(TroubleshootingQueryTemplateEntity::getTemplateKey, templateKey.trim())
                            .last("LIMIT 1"));
            return Optional.ofNullable(byKey);
        }

        TroubleshootingQueryTemplateEntity defaultTemplate = templateMapper.selectOne(
                baseLookup(workspaceId, normalizedProvider, normalizedEvidenceType)
                        .eq(TroubleshootingQueryTemplateEntity::getDefaultTemplate, 1)
                        .orderByDesc(TroubleshootingQueryTemplateEntity::getPriority)
                        .orderByDesc(TroubleshootingQueryTemplateEntity::getUpdateTime)
                        .last("LIMIT 1"));
        return Optional.ofNullable(defaultTemplate);
    }

    public Optional<TroubleshootingQueryTemplateEntity> resolveForAlert(long workspaceId,
                                                                        String provider,
                                                                        String evidenceType,
                                                                        String templateKey,
                                                                        SopRouteRequest alert) {
        if (templateKey != null && !templateKey.isBlank()) {
            return resolve(workspaceId, provider, evidenceType, templateKey);
        }

        String normalizedProvider = normalize(value(provider, DEFAULT_PROVIDER));
        String normalizedEvidenceType = normalize(value(evidenceType, DEFAULT_EVIDENCE_TYPE));
        List<TroubleshootingQueryTemplateEntity> candidates = templateMapper.selectList(
                baseLookup(workspaceId, normalizedProvider, normalizedEvidenceType)
                        .orderByDesc(TroubleshootingQueryTemplateEntity::getPriority)
                        .orderByDesc(TroubleshootingQueryTemplateEntity::getDefaultTemplate)
                        .orderByAsc(TroubleshootingQueryTemplateEntity::getTemplateKey));

        return candidates.stream()
                .map(template -> new MatchedTemplate(template, matchScore(template, alert)))
                .filter(item -> item.score().matched() && item.score().predicateCount() > 0)
                .max(Comparator
                        .comparingInt((MatchedTemplate item) -> item.score().score())
                        .thenComparingInt(item -> value(item.template().getPriority(), 0))
                        .thenComparingInt(item -> intFlag(item.template().getDefaultTemplate(), false) ? 1 : 0))
                .map(MatchedTemplate::template)
                .or(() -> resolve(workspaceId, provider, evidenceType, null));
    }

    @Transactional
    public TroubleshootingQueryTemplateEntity create(long workspaceId, TroubleshootingQueryTemplateRequest request) {
        TroubleshootingQueryTemplateEntity entity = new TroubleshootingQueryTemplateEntity();
        entity.setWorkspaceId(workspaceId);
        apply(entity, request);
        ensureUniqueKey(workspaceId, entity.getProvider(), entity.getEvidenceType(), entity.getTemplateKey(), null);
        templateMapper.insert(entity);
        return entity;
    }

    @Transactional
    public TroubleshootingQueryTemplateEntity update(long workspaceId,
                                                    long id,
                                                    TroubleshootingQueryTemplateRequest request) {
        TroubleshootingQueryTemplateEntity existing = get(workspaceId, id);
        apply(existing, request);
        ensureUniqueKey(workspaceId, existing.getProvider(), existing.getEvidenceType(), existing.getTemplateKey(), id);
        templateMapper.updateById(existing);
        return get(workspaceId, id);
    }

    @Transactional
    public List<TroubleshootingQueryTemplateEntity> seedGuanceDefaultTemplates(long workspaceId) {
        List<TroubleshootingQueryTemplateEntity> seeded = new ArrayList<>();
        for (TroubleshootingQueryTemplateRequest request : guanceDefaultTemplates()) {
            String provider = normalize(value(request.provider(), DEFAULT_PROVIDER));
            String evidenceType = normalize(value(request.evidenceType(), DEFAULT_EVIDENCE_TYPE));
            TroubleshootingQueryTemplateEntity existing = templateMapper.selectOne(
                    new LambdaQueryWrapper<TroubleshootingQueryTemplateEntity>()
                            .eq(TroubleshootingQueryTemplateEntity::getWorkspaceId, workspaceId)
                            .eq(TroubleshootingQueryTemplateEntity::getProvider, provider)
                            .eq(TroubleshootingQueryTemplateEntity::getEvidenceType, evidenceType)
                            .eq(TroubleshootingQueryTemplateEntity::getTemplateKey, request.templateKey())
                            .eq(TroubleshootingQueryTemplateEntity::getDeleted, 0)
                            .last("LIMIT 1"));
            if (existing != null) {
                seeded.add(existing);
                continue;
            }
            seeded.add(create(workspaceId, request));
        }
        return seeded;
    }

    @Transactional
    public void delete(long workspaceId, long id) {
        TroubleshootingQueryTemplateEntity existing = get(workspaceId, id);
        existing.setDeleted(1);
        templateMapper.updateById(existing);
    }

    public TroubleshootingQueryTemplateEntity get(long workspaceId, long id) {
        TroubleshootingQueryTemplateEntity entity = templateMapper.selectById(id);
        if (entity == null || entity.getWorkspaceId() == null || entity.getWorkspaceId() != workspaceId
                || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new MateClawException("Troubleshooting query template not found: " + id);
        }
        return entity;
    }

    private LambdaQueryWrapper<TroubleshootingQueryTemplateEntity> baseLookup(long workspaceId,
                                                                             String provider,
                                                                             String evidenceType) {
        return new LambdaQueryWrapper<TroubleshootingQueryTemplateEntity>()
                .eq(TroubleshootingQueryTemplateEntity::getWorkspaceId, workspaceId)
                .eq(TroubleshootingQueryTemplateEntity::getProvider, provider)
                .eq(TroubleshootingQueryTemplateEntity::getEvidenceType, evidenceType)
                .eq(TroubleshootingQueryTemplateEntity::getEnabled, 1)
                .eq(TroubleshootingQueryTemplateEntity::getDeleted, 0);
    }

    private void apply(TroubleshootingQueryTemplateEntity entity, TroubleshootingQueryTemplateRequest request) {
        if (request == null) {
            throw new MateClawException("Query template request is required");
        }
        String provider = normalize(value(request.provider(), DEFAULT_PROVIDER));
        String evidenceType = normalize(value(request.evidenceType(), DEFAULT_EVIDENCE_TYPE));
        String templateKey = required(request.templateKey(), "templateKey");
        String payloadTemplate = required(request.payloadTemplate(), "payloadTemplate");
        validateJsonTemplate(payloadTemplate, "payloadTemplate");
        validateJson(request.matchJson(), "matchJson");

        entity.setProvider(provider);
        entity.setEvidenceType(evidenceType);
        entity.setTemplateKey(templateKey);
        entity.setName(value(request.name(), templateKey));
        entity.setDescription(blankToNull(request.description()));
        entity.setPayloadTemplate(payloadTemplate);
        entity.setDqlTemplate(blankToNull(request.dqlTemplate()));
        entity.setMatchJson(blankToNull(request.matchJson()));
        entity.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        entity.setDefaultTemplate(Boolean.TRUE.equals(request.defaultTemplate()) ? 1 : 0);
        entity.setPriority(request.priority() == null ? 0 : request.priority());
        entity.setDeleted(0);
    }

    private void ensureUniqueKey(long workspaceId,
                                 String provider,
                                 String evidenceType,
                                 String templateKey,
                                 Long ignoreId) {
        TroubleshootingQueryTemplateEntity existing = templateMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingQueryTemplateEntity>()
                        .eq(TroubleshootingQueryTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingQueryTemplateEntity::getProvider, provider)
                        .eq(TroubleshootingQueryTemplateEntity::getEvidenceType, evidenceType)
                        .eq(TroubleshootingQueryTemplateEntity::getTemplateKey, templateKey)
                        .eq(TroubleshootingQueryTemplateEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (existing != null && (ignoreId == null || !ignoreId.equals(existing.getId()))) {
            throw new MateClawException("Query template key already exists: " + templateKey);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MateClawException("Query template " + field + " is required");
        }
        return value.trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<TroubleshootingQueryTemplateRequest> guanceDefaultTemplates() {
        return List.of(
                new TroubleshootingQueryTemplateRequest(
                        "guance",
                        "synthetics",
                        "guance-http-dial-by-name",
                        "观测云 HTTP 拨测 - 按任务名",
                        "适用于观测云「可用性检测 > 任务」的 http_dial_testing 查询。",
                        GUANCE_DQL_PAYLOAD_TEMPLATE,
                        "D::http_dial_testing:(`status_code`, `url`, `name`) { `name` = '${syntheticsTaskNameDql}' }",
                        """
                                {
                                  "labelExists": ["syntheticsTaskName"],
                                  "metricNames": ["synthetics_status_code"],
                                  "keywords": ["可用性检测", "拨测", "http_dial_testing"]
                                }
                                """,
                        true,
                        true,
                        100
                ),
                new TroubleshootingQueryTemplateRequest(
                        "guance",
                        "host",
                        "guance-host-by-name",
                        "观测云基础设施主机 - 按主机名",
                        "适用于观测云「基础设施 > 主机」的 DQL 查询，按 hostName/host 标签匹配。",
                        GUANCE_DQL_PAYLOAD_TEMPLATE,
                        "D::host:(`host`, `host_name`, `ip`, `cpu_usage`, `mem_used_percent`, `status`) { `host` = '${hostNameDql}' }",
                        """
                                {
                                  "hostNames": ["*"],
                                  "keywords": ["主机", "host", "cpu", "memory"]
                                }
                                """,
                        true,
                        true,
                        90
                ),
                new TroubleshootingQueryTemplateRequest(
                        "guance",
                        "container",
                        "guance-container-by-pod",
                        "观测云基础设施容器 - 按 Pod",
                        "适用于观测云「基础设施 > 容器」的 DQL 查询，按 pod/container 标签匹配。",
                        GUANCE_DQL_PAYLOAD_TEMPLATE,
                        "D::container:(`container_name`, `pod_name`, `namespace`, `cluster`, `status`, `restart_count`) { `pod_name` = '${podNameDql}' }",
                        """
                                {
                                  "podNames": ["*"],
                                  "keywords": ["容器", "container", "pod", "restart"]
                                }
                                """,
                        true,
                        true,
                        90
                ),
                new TroubleshootingQueryTemplateRequest(
                        "guance",
                        "k8s",
                        "guance-k8s-by-namespace",
                        "观测云 K8s 事件 - 按命名空间",
                        "适用于观测云 K8s/容器事件查询，按 namespace/cluster 标签匹配。",
                        GUANCE_DQL_PAYLOAD_TEMPLATE,
                        "D::container:(`container_name`, `pod_name`, `namespace`, `cluster`, `status`, `restart_count`) { `namespace` = '${namespaceDql}' }",
                        """
                                {
                                  "namespaces": ["*"],
                                  "keywords": ["k8s", "pod", "restart", "probe"]
                                }
                                """,
                        true,
                        true,
                        80
                ),
                new TroubleshootingQueryTemplateRequest(
                        "guance",
                        "metrics",
                        "guance-metrics-by-service",
                        "观测云指标查询 - 按服务",
                        "适用于观测云指标 DQL 查询，按服务、指标或告警关键词匹配。",
                        GUANCE_DQL_PAYLOAD_TEMPLATE,
                        "M::`${metricNameIdentifier}`:(*) { `service` = '${serviceNameDql}' }",
                        """
                                {
                                  "keywords": ["指标", "metrics", "5xx", "latency", "timeout"]
                                }
                                """,
                        true,
                        true,
                        80
                )
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateJsonTemplate(String template, String field) {
        try {
            objectMapper.readTree(renderJsonTemplateForValidation(template));
        } catch (Exception e) {
            throw new MateClawException("Query template " + field + " must be valid JSON; placeholders can be used inside strings or as full JSON values");
        }
    }

    private static String renderJsonTemplateForValidation(String template) {
        var matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = isInsideJsonString(template, matcher.start()) ? "" : "\"\"";
            matcher.appendReplacement(rendered, replacement);
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static boolean isInsideJsonString(String template, int position) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < position; i++) {
            char ch = template.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
            }
        }
        return inString;
    }

    private void validateJson(String value, String field) {
        if (value == null || value.isBlank()) return;
        try {
            objectMapper.readTree(value);
        } catch (Exception e) {
            throw new MateClawException("Query template " + field + " must be valid JSON");
        }
    }

    private MatchScore matchScore(TroubleshootingQueryTemplateEntity template, SopRouteRequest alert) {
        String matchJson = template.getMatchJson();
        if (matchJson == null || matchJson.isBlank()) {
            return new MatchScore(true, 0, 0);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(matchJson);
        } catch (Exception e) {
            return new MatchScore(false, 0, 0);
        }
        if (root == null || !root.isObject()) {
            return new MatchScore(false, 0, 0);
        }

        int predicates = 0;
        int score = 0;
        FieldMatch fieldMatch = new FieldMatch(alert);
        for (String key : List.of("severities", "severity")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.severity(), root.get(key))) return MatchScore.no(predicates);
                score += 10;
            }
        }
        for (String key : List.of("serviceNames", "serviceName", "services")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.serviceName(), root.get(key))) return MatchScore.no(predicates);
                score += 10;
            }
        }
        for (String key : List.of("envs", "env")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.env(), root.get(key))) return MatchScore.no(predicates);
                score += 8;
            }
        }
        for (String key : List.of("clusters", "cluster")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.cluster(), root.get(key))) return MatchScore.no(predicates);
                score += 8;
            }
        }
        for (String key : List.of("namespaces", "namespace")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.namespace(), root.get(key))) return MatchScore.no(predicates);
                score += 8;
            }
        }
        for (String key : List.of("endpoints", "endpoint")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.endpoint(), root.get(key))) return MatchScore.no(predicates);
                score += 8;
            }
        }
        for (String key : List.of("metricNames", "metricName", "metrics")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.metricName(), root.get(key))) return MatchScore.no(predicates);
                score += 10;
            }
        }
        for (String key : List.of("alertNames", "alertName")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.alertName(), root.get(key))) return MatchScore.no(predicates);
                score += 8;
            }
        }
        for (String key : List.of("taskNames", "taskName", "syntheticsTaskNames", "syntheticsTaskName")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.taskName(), root.get(key))) return MatchScore.no(predicates);
                score += 12;
            }
        }
        for (String key : List.of("hostNames", "hostName", "hosts", "host")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.hostName(), root.get(key))) return MatchScore.no(predicates);
                score += 12;
            }
        }
        for (String key : List.of("podNames", "podName", "pods", "pod")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.podName(), root.get(key))) return MatchScore.no(predicates);
                score += 12;
            }
        }
        for (String key : List.of("containerNames", "containerName", "containers", "container")) {
            if (root.has(key)) {
                predicates++;
                if (!matchesAny(fieldMatch.containerName(), root.get(key))) return MatchScore.no(predicates);
                score += 12;
            }
        }
        if (root.has("labelExists")) {
            int count = labelExistsCount(root.get("labelExists"));
            predicates += count;
            if (count == 0 || !labelsExist(root.get("labelExists"), fieldMatch.labels())) return MatchScore.no(predicates);
            score += count * 6;
        }
        if (root.has("labels")) {
            JsonNode labels = root.get("labels");
            int count = labels == null || !labels.isObject() ? 1 : labels.size();
            predicates += count;
            if (!labelsMatch(labels, fieldMatch.labels())) return MatchScore.no(predicates);
            score += count * 10;
        }
        if (root.has("labelContains")) {
            JsonNode labels = root.get("labelContains");
            int count = labels == null || !labels.isObject() ? 1 : labels.size();
            predicates += count;
            if (!labelsContain(labels, fieldMatch.labels())) return MatchScore.no(predicates);
            score += count * 8;
        }
        if (root.has("keywords")) {
            predicates++;
            if (!containsAny(fieldMatch.searchText(), root.get("keywords"))) return MatchScore.no(predicates);
            score += 6;
        }

        return new MatchScore(true, predicates, score);
    }

    private static boolean labelsExist(JsonNode node, Map<String, Object> labels) {
        List<String> keys = stringValues(node);
        if (keys.isEmpty()) return false;
        for (String key : keys) {
            Object value = labels.get(key);
            if (value == null || String.valueOf(value).isBlank()) return false;
        }
        return true;
    }

    private static int labelExistsCount(JsonNode node) {
        return stringValues(node).size();
    }

    private static boolean labelsMatch(JsonNode node, Map<String, Object> labels) {
        if (node == null || !node.isObject()) return false;
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Object actual = labels.get(field.getKey());
            if (!matchesAny(actual == null ? "" : String.valueOf(actual), field.getValue())) return false;
        }
        return true;
    }

    private static boolean labelsContain(JsonNode node, Map<String, Object> labels) {
        if (node == null || !node.isObject()) return false;
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Object actual = labels.get(field.getKey());
            if (!containsAny(actual == null ? "" : String.valueOf(actual), field.getValue())) return false;
        }
        return true;
    }

    private static boolean matchesAny(String actual, JsonNode expected) {
        String normalizedActual = normalizeForMatch(actual);
        if (normalizedActual.isBlank()) return false;
        return stringValues(expected).stream().anyMatch(value -> matchValue(normalizedActual, value));
    }

    private static boolean containsAny(String haystack, JsonNode needles) {
        String normalizedHaystack = normalizeForMatch(haystack);
        if (normalizedHaystack.isBlank()) return false;
        return stringValues(needles).stream()
                .map(TroubleshootingQueryTemplateService::normalizeForMatch)
                .filter(value -> !value.isBlank())
                .anyMatch(normalizedHaystack::contains);
    }

    private static boolean matchValue(String normalizedActual, String expected) {
        String normalizedExpected = normalizeForMatch(expected);
        if (normalizedExpected.isBlank()) return false;
        if ("*".equals(normalizedExpected)) return true;
        if (normalizedExpected.endsWith("*") && normalizedActual.startsWith(
                normalizedExpected.substring(0, normalizedExpected.length() - 1))) {
            return true;
        }
        if (normalizedExpected.startsWith("*") && normalizedActual.endsWith(normalizedExpected.substring(1))) {
            return true;
        }
        return normalizedActual.equals(normalizedExpected) || normalizedActual.contains(normalizedExpected);
    }

    private static List<String> stringValues(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                    .map(TroubleshootingQueryTemplateService::nodeText)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String value = nodeText(node);
        return value.isBlank() ? List.of() : List.of(value);
    }

    private static String nodeText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isNumber() || node.isBoolean()) return node.asText();
        return node.toString();
    }

    private static String normalizeForMatch(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static boolean intFlag(Integer value, boolean defaultValue) {
        return value == null ? defaultValue : value != 0;
    }

    private record MatchedTemplate(TroubleshootingQueryTemplateEntity template, MatchScore score) {
    }

    private record MatchScore(boolean matched, int predicateCount, int score) {
        static MatchScore no(int predicateCount) {
            return new MatchScore(false, predicateCount, 0);
        }
    }

    private record FieldMatch(SopRouteRequest alert) {
        Map<String, Object> labels() {
            return alert == null ? Map.of() : alert.safeLabels();
        }

        String severity() {
            return alert == null ? "" : value(alert.severity(), "");
        }

        String serviceName() {
            return alert == null ? "" : value(alert.serviceName(), "");
        }

        String env() {
            return alert == null ? "" : value(alert.env(), "");
        }

        String cluster() {
            return alert == null ? "" : value(alert.cluster(), "");
        }

        String namespace() {
            return alert == null ? "" : value(alert.namespace(), "");
        }

        String endpoint() {
            return alert == null ? "" : value(alert.endpoint(), "");
        }

        String metricName() {
            return alert == null ? "" : value(alert.metricName(), "");
        }

        String alertName() {
            return alert == null ? "" : value(alert.alertName(), "");
        }

        String taskName() {
            for (String key : List.of(
                    "syntheticsTaskName",
                    "synthetics_task_name",
                    "dialTaskName",
                    "dial_task_name",
                    "taskName",
                    "task_name",
                    "checkName",
                    "check_name",
                    "availabilityTaskName",
                    "availability_task_name",
                    "name",
                    "拨测任务"
            )) {
                Object value = labels().get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value).trim();
                }
            }
            return alertName();
        }

        String hostName() {
            String fromLabel = labelValue(List.of("hostName", "host_name", "hostname", "host", "nodeName", "node_name", "node"));
            if (!fromLabel.isBlank()) return fromLabel;
            return alert == null ? "" : value(alert.instance(), "");
        }

        String podName() {
            String fromLabel = labelValue(List.of("podName", "pod_name", "pod"));
            if (!fromLabel.isBlank()) return fromLabel;
            return alert == null ? "" : value(alert.pod(), "");
        }

        String containerName() {
            return labelValue(List.of("containerName", "container_name", "container", "containerId", "container_id"));
        }

        String labelValue(List<String> keys) {
            for (String key : keys) {
                Object value = labels().get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value).trim();
                }
            }
            return "";
        }

        String searchText() {
            if (alert == null) return "";
            return Stream.concat(
                            Stream.of(
                                    alert.alertName(),
                                    alert.message(),
                                    alert.rawText(),
                                    alert.serviceName(),
                                    alert.env(),
                                    alert.cluster(),
                                    alert.namespace(),
                                    alert.endpoint(),
                                    alert.metricName(),
                                    taskName(),
                                    hostName(),
                                    podName(),
                                    containerName()
                            ),
                            labels().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                    )
                    .filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(String::valueOf)
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
    }
}
