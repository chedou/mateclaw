package vip.mate.troubleshooting.investigation;

import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.EvidenceRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable graph of server-authored candidate causes and evidence questions. */
public final class HypothesisGraph {

    private final Map<String, Node> nodes;
    private final Map<String, QuestionState> questions;

    private HypothesisGraph(Map<String, Node> nodes, Map<String, QuestionState> questions) {
        this.nodes = Map.copyOf(nodes);
        this.questions = Map.copyOf(questions);
    }

    public static HypothesisGraph of(List<Hypothesis> hypotheses) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, QuestionState> questions = new LinkedHashMap<>();
        for (Hypothesis hypothesis : hypotheses == null
                ? List.<Hypothesis>of() : hypotheses) {
            if (nodes.putIfAbsent(
                    hypothesis.hypothesisId(),
                    new Node(
                            hypothesis.hypothesisId(),
                            hypothesis.statement(),
                            hypothesis.priority(),
                            Status.PENDING,
                            hypothesis.questions(),
                            List.of())) != null) {
                throw new IllegalArgumentException(
                        "duplicate hypothesis: " + hypothesis.hypothesisId());
            }
            for (Question question : hypothesis.questions()) {
                if (questions.putIfAbsent(
                        question.questionId(),
                        new QuestionState(hypothesis.hypothesisId(), question, null, null)) != null) {
                    throw new IllegalArgumentException(
                            "duplicate investigation question: " + question.questionId());
                }
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("hypotheses must not be empty");
        }
        return new HypothesisGraph(nodes, questions);
    }

    public Optional<Question> nextQuestion() {
        return questions.values().stream()
                .filter(state -> state.outcome() == null)
                .filter(state -> nodes.get(state.hypothesisId()).status() == Status.PENDING)
                .sorted(Comparator
                        .comparingInt((QuestionState state) ->
                                nodes.get(state.hypothesisId()).priority()).reversed()
                        .thenComparing(
                                Comparator.comparingInt(
                                        (QuestionState state) -> state.question().priority())
                                        .reversed())
                        .thenComparing(state -> state.question().questionId()))
                .map(QuestionState::question)
                .findFirst();
    }

    public HypothesisGraph recordOutcome(
            String questionId,
            CriterionOutcome outcome,
            String evidenceRef) {
        String id = required(questionId, "questionId");
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        QuestionState current = questions.get(id);
        if (current == null) {
            throw new IllegalArgumentException("unknown investigation question: " + id);
        }
        if (current.outcome() != null) {
            throw new IllegalStateException("investigation question is already answered: " + id);
        }
        Map<String, QuestionState> nextQuestions = new LinkedHashMap<>(questions);
        nextQuestions.put(id, new QuestionState(
                current.hypothesisId(),
                current.question(),
                outcome,
                normalizeNullable(evidenceRef)));

        Map<String, Node> nextNodes = new LinkedHashMap<>(nodes);
        Node currentNode = nodes.get(current.hypothesisId());
        List<QuestionState> nodeQuestions = nextQuestions.values().stream()
                .filter(state -> state.hypothesisId().equals(current.hypothesisId()))
                .toList();
        Status status = deriveStatus(nodeQuestions);
        LinkedHashSet<String> refs = new LinkedHashSet<>(currentNode.evidenceRefs());
        nodeQuestions.stream()
                .map(QuestionState::evidenceRef)
                .filter(java.util.Objects::nonNull)
                .forEach(refs::add);
        nextNodes.put(current.hypothesisId(), new Node(
                currentNode.hypothesisId(),
                currentNode.statement(),
                currentNode.priority(),
                status,
                currentNode.questions(),
                List.copyOf(refs)));
        return new HypothesisGraph(nextNodes, nextQuestions);
    }

    public Node node(String hypothesisId) {
        Node node = nodes.get(required(hypothesisId, "hypothesisId"));
        if (node == null) {
            throw new IllegalArgumentException("unknown hypothesis: " + hypothesisId);
        }
        return node;
    }

    public List<Node> nodes() {
        return nodes.values().stream()
                .sorted(Comparator.comparingInt(Node::priority).reversed()
                        .thenComparing(Node::hypothesisId))
                .toList();
    }

    private static Status deriveStatus(List<QuestionState> states) {
        if (states.stream().anyMatch(state -> state.outcome() == CriterionOutcome.EXCLUDED)) {
            return Status.EXCLUDED;
        }
        if (states.stream().allMatch(state -> state.outcome() == CriterionOutcome.SATISFIED)) {
            return Status.SUPPORTED;
        }
        if (states.stream().allMatch(state -> state.outcome() != null)) {
            return Status.UNKNOWN;
        }
        return Status.PENDING;
    }

    public enum Status {
        PENDING,
        SUPPORTED,
        EXCLUDED,
        UNKNOWN
    }

    public record Hypothesis(
            String hypothesisId,
            String statement,
            int priority,
            List<Question> questions) {

        public Hypothesis {
            hypothesisId = required(hypothesisId, "hypothesisId");
            statement = required(statement, "statement");
            if (priority < 0) {
                throw new IllegalArgumentException("priority must not be negative");
            }
            questions = List.copyOf(questions == null ? List.of() : questions);
            if (questions.isEmpty()) {
                throw new IllegalArgumentException("hypothesis questions must not be empty");
            }
        }
    }

    public record Question(
            String questionId,
            int priority,
            String toolKey,
            String toolVersion,
            EvidenceRequest request,
            AnomalyCriterion criterion) {

        public Question {
            questionId = required(questionId, "questionId");
            toolKey = required(toolKey, "toolKey");
            toolVersion = required(toolVersion, "toolVersion");
            if (priority < 0 || request == null || criterion == null) {
                throw new IllegalArgumentException(
                        "priority, request and criterion are required");
            }
            if (!questionId.equals(request.requestId())
                    || !request.requestId().equals(criterion.sourceRequestId())) {
                throw new IllegalArgumentException(
                        "question, evidence request and criterion ids must match");
            }
        }

        public String toolIdentity() {
            return toolKey + "@" + toolVersion;
        }
    }

    public record Node(
            String hypothesisId,
            String statement,
            int priority,
            Status status,
            List<Question> questions,
            List<String> evidenceRefs) {

        public Node {
            questions = List.copyOf(questions);
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    private record QuestionState(
            String hypothesisId,
            Question question,
            CriterionOutcome outcome,
            String evidenceRef) {
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
