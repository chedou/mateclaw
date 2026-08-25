package vip.mate.tool.itdb;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

@Service
class ItDbSqlReviewService {

    private static final long DIRECT_APPROVAL_MAX_ROWS = 10;
    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(?i)(password|passwd|secret|access[_-]?key|api[_-]?key|token|private[_-]?key)");

    ItDbReview review(ItDbTicket ticket, ItDbSqlCheck check, boolean currentlyPending) {
        List<String> blockers = new ArrayList<>();
        List<String> residual = new ArrayList<>();
        List<String> evidence = new ArrayList<>();

        if (!currentlyPending) {
            blockers.add("工单不在当前审批人的实时待办列表中");
        }
        if (!"workflow_manreviewing".equalsIgnoreCase(ticket.status())) {
            blockers.add("工单当前状态不是人工审核中: " + safe(ticket.status()));
        }
        if (!ticket.backup()) {
            blockers.add("工单未声明备份");
        }
        if (check.errorCount() > 0 || hasPlatformMessages(check.error())) {
            blockers.add("ITDB SQL 检查存在错误");
        }
        if (check.critical()) {
            blockers.add("ITDB SQL 检查标记为 critical");
        }
        if (check.warningCount() > 0 || hasPlatformMessages(check.warning())) {
            residual.add("ITDB SQL 检查存在 warning");
        }
        if (check.affectedRows() < 0 || check.affectedRows() > DIRECT_APPROVAL_MAX_ROWS) {
            residual.add("预计影响行数超出直接审批阈值 0-" + DIRECT_APPROVAL_MAX_ROWS);
        }
        if (!validExecutionWindow(ticket.runDateStart(), ticket.runDateEnd())) {
            residual.add("执行时间窗口缺失、无效或已经过期");
        }
        if (ticket.manualMode() != 0) {
            residual.add("工单标记为手工执行模式");
        }
        if (CREDENTIAL_LIKE.matcher(safe(ticket.sqlContent())).find()) {
            residual.add("SQL 包含疑似凭据或密钥字段，需要人工核对脱敏与轮换方案");
        }

        SqlShape shape = inspectSql(ticket.sqlContent(), blockers, residual);
        evidence.add("ITDB status=" + safe(check.status()));
        evidence.add("ITDB checked=" + safe(check.checked()));
        evidence.add("syntaxType=" + ticket.syntaxType());
        evidence.add("backup=" + ticket.backup());
        evidence.add("affectedRows=" + check.affectedRows());
        evidence.add("statementCount=" + shape.statementCount());
        evidence.add("currentAudit=" + safe(ticket.currentAudit()));

        ItDbRecommendation recommendation;
        ItDbRiskLevel riskLevel;
        boolean cleanPlatformCheck = check.errorCount() == 0
                && check.warningCount() == 0
                && !check.critical()
                && platformCheckPassed(check);
        if (!platformCheckPassed(check)) {
            residual.add("ITDB SQL 检查结果未明确且一致地通过");
        }
        boolean directApprovalCandidate = blockers.isEmpty()
                && residual.isEmpty()
                && shape.onlyBoundedDml()
                && ticket.syntaxType() == 2
                && check.syntaxType() == 2
                && cleanPlatformCheck
                && check.affectedRows() >= 0
                && check.affectedRows() <= DIRECT_APPROVAL_MAX_ROWS;

        if (!blockers.isEmpty()) {
            recommendation = ItDbRecommendation.REJECT;
            riskLevel = ItDbRiskLevel.CRITICAL;
        } else if (directApprovalCandidate) {
            recommendation = ItDbRecommendation.APPROVE;
            riskLevel = ItDbRiskLevel.LOW;
        } else {
            recommendation = ItDbRecommendation.MANUAL_REVIEW;
            riskLevel = ticket.syntaxType() == 1 || shape.hasDdl()
                    ? ItDbRiskLevel.HIGH
                    : ItDbRiskLevel.MEDIUM;
        }

        return new ItDbReview(
                riskLevel,
                recommendation,
                recommendation == ItDbRecommendation.APPROVE,
                false,
                check.affectedRows(),
                List.copyOf(blockers),
                List.copyOf(residual),
                List.copyOf(evidence));
    }

    private SqlShape inspectSql(String sql, List<String> blockers, List<String> residual) {
        if (sql == null || sql.isBlank()) {
            blockers.add("SQL 内容为空");
            return new SqlShape(0, false, false);
        }
        try {
            List<Statement> statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            boolean onlyBoundedDml = !statements.isEmpty();
            boolean hasDdl = false;
            if (statements.size() > 1) {
                residual.add("多条 SQL 语句不进入直接审批路径");
                onlyBoundedDml = false;
            }
            for (Statement statement : statements) {
                if (statement instanceof Update update) {
                    if (hasUpdateJoinOrFrom(update)) {
                        residual.add("UPDATE JOIN/FROM/多表更新不进入直接审批路径");
                        onlyBoundedDml = false;
                    } else if (update.getWhere() == null) {
                        blockers.add("UPDATE 缺少 WHERE 条件");
                        onlyBoundedDml = false;
                    } else if (!hasPrimaryKeyLiteralPredicate(update.getWhere(), update.getTable())) {
                        residual.add("UPDATE 未证明由主键 id 的字面量等值条件限定");
                        onlyBoundedDml = false;
                    }
                } else if (statement instanceof Delete delete) {
                    if (hasDeleteJoinOrMultipleTargets(delete)) {
                        residual.add("DELETE JOIN/USING/多表删除不进入直接审批路径");
                        onlyBoundedDml = false;
                    } else if (delete.getWhere() == null) {
                        blockers.add("DELETE 缺少 WHERE 条件");
                        onlyBoundedDml = false;
                    } else if (!hasPrimaryKeyLiteralPredicate(delete.getWhere(), delete.getTable())) {
                        residual.add("DELETE 未证明由主键 id 的字面量等值条件限定");
                        onlyBoundedDml = false;
                    }
                } else if (statement instanceof Insert insert) {
                    if (!insert.isUseValues()) {
                        residual.add("INSERT ... SELECT 不进入直接审批路径");
                        onlyBoundedDml = false;
                    }
                } else {
                    onlyBoundedDml = false;
                    String type = statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
                    hasDdl = type.contains("ALTER") || type.contains("CREATE") || type.contains("DROP")
                            || type.contains("TRUNCATE") || type.contains("RENAME");
                    residual.add("包含非受限 DML 语句: " + type);
                }
            }
            return new SqlShape(statements.size(), onlyBoundedDml, hasDdl);
        } catch (JSQLParserException e) {
            blockers.add("SQL 无法由安全解析器完整解析");
            return new SqlShape(0, false, false);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasPlatformMessages(String value) {
        String normalized = safe(value).strip();
        return !normalized.isEmpty()
                && !"[]".equals(normalized)
                && !"{}".equals(normalized)
                && !"null".equalsIgnoreCase(normalized)
                && !"\"null\"".equalsIgnoreCase(normalized);
    }

    private static boolean platformCheckPassed(ItDbSqlCheck check) {
        List<String> values = List.of(safe(check.status()), safe(check.checked())).stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
        return !values.isEmpty() && values.stream().allMatch(ItDbSqlReviewService::isPassedValue);
    }

    private static boolean isPassedValue(String value) {
        return switch (safe(value).strip().toLowerCase(Locale.ROOT)) {
            case "true", "pass", "passed", "success" -> true;
            default -> false;
        };
    }

    private static boolean hasPrimaryKeyLiteralPredicate(Expression expression, Table target) {
        if (expression instanceof ParenthesedExpressionList<?> parenthesized
                && parenthesized.size() == 1) {
            return hasPrimaryKeyLiteralPredicate(parenthesized.getFirst(), target);
        }
        if (expression instanceof AndExpression andExpression) {
            return hasPrimaryKeyLiteralPredicate(andExpression.getLeftExpression(), target)
                    || hasPrimaryKeyLiteralPredicate(andExpression.getRightExpression(), target);
        }
        if (!(expression instanceof EqualsTo equalsTo)) {
            return false;
        }
        return isTargetIdColumn(equalsTo.getLeftExpression(), target) && isLiteral(equalsTo.getRightExpression())
                || isTargetIdColumn(equalsTo.getRightExpression(), target) && isLiteral(equalsTo.getLeftExpression());
    }

    private static boolean isTargetIdColumn(Expression expression, Table target) {
        if (!(expression instanceof Column column) || !"id".equalsIgnoreCase(column.getColumnName())) {
            return false;
        }
        String qualifier = column.getTable() == null ? "" : safe(column.getTable().getName()).strip();
        if (qualifier.isEmpty()) {
            return true;
        }
        String tableName = target == null ? "" : safe(target.getName()).strip();
        String alias = target == null || target.getAlias() == null ? "" : safe(target.getAlias().getName()).strip();
        return qualifier.equalsIgnoreCase(tableName) || qualifier.equalsIgnoreCase(alias);
    }

    private static boolean hasUpdateJoinOrFrom(Update update) {
        return update.getFromItem() != null
                || update.getSelect() != null
                || update.getJoins() != null && !update.getJoins().isEmpty()
                || update.getStartJoins() != null && !update.getStartJoins().isEmpty();
    }

    private static boolean hasDeleteJoinOrMultipleTargets(Delete delete) {
        return delete.getJoins() != null && !delete.getJoins().isEmpty()
                || delete.getUsingList() != null && !delete.getUsingList().isEmpty()
                || delete.getTables() != null && delete.getTables().size() > 1;
    }

    private static boolean validExecutionWindow(String startValue, String endValue) {
        Instant start = parseInstant(startValue);
        Instant end = parseInstant(endValue);
        return start != null && end != null && end.isAfter(start) && end.isAfter(Instant.now());
    }

    private static Instant parseInstant(String value) {
        String normalized = safe(value).strip();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                DateTimeFormatter formatter = normalized.contains("T")
                        ? DateTimeFormatter.ISO_LOCAL_DATE_TIME
                        : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                return LocalDateTime.parse(normalized, formatter)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static boolean isLiteral(Expression expression) {
        return expression instanceof LongValue || expression instanceof StringValue;
    }

    private record SqlShape(int statementCount, boolean onlyBoundedDml, boolean hasDdl) {
    }
}
