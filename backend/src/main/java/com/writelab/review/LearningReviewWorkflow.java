package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.RankedGroundingMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Learning Review workflow. Control flow is fixed in application code: read the recent window,
 * group Learning Issues by category and severity, keep only patterns that recur across attempts,
 * retrieve Grounding Material for each pattern, then order the Study Plan. There is no model
 * decision here, which is what makes the review reproducible.
 *
 * <p>Recurrence is counted per Practice Attempt, not per issue row: two issues of the same category
 * in one essay are one occurrence of the pattern, so a single bad day cannot outrank a pattern the
 * learner keeps repeating.</p>
 */
public class LearningReviewWorkflow {

    private static final Logger log = LoggerFactory.getLogger(LearningReviewWorkflow.class);

    public static final int DEFAULT_RECENT_ATTEMPT_LIMIT = 10;
    public static final int MIN_ATTEMPTS = 3;
    public static final int MIN_RECURRING_ATTEMPTS = 2;
    public static final int MIN_LEVEL_TREND_ATTEMPTS = 2;

    /**
     * Ranking weights. One more Practice Attempt showing the pattern dominates raw issue count, so a
     * MAJOR pattern seen in three essays outranks a MINOR pattern repeated many times in two. The
     * numbers are scores, not learner-facing percentages.
     */
    private static final int ATTEMPT_WEIGHT = 10;
    private static final int OCCURRENCE_WEIGHT = 2;
    private static final int MAJOR_BONUS = 5;

    private final LearningMaterialRetriever materialRetriever;

    public LearningReviewWorkflow(LearningMaterialRetriever materialRetriever) {
        this.materialRetriever = materialRetriever;
    }

    public LearningReviewResponse review(List<PracticeAttemptSnapshot> attempts) {
        List<PracticeAttemptSnapshot> ordered = attempts == null ? List.of() : attempts.stream()
                .sorted(Comparator.comparing(PracticeAttemptSnapshot::createdAt)
                        .thenComparing(PracticeAttemptSnapshot::practiceAttemptId))
                .toList();
        EstimatedLevelChange levelChange = estimatedLevelChange(ordered);

        if (ordered.size() < MIN_ATTEMPTS) {
            return new LearningReviewResponse(
                    LearningReviewStatus.INSUFFICIENT_DATA,
                    "最近只有 " + ordered.size() + " 次 Practice Attempt，至少需要 " + MIN_ATTEMPTS
                            + " 次才能汇总出可靠的 Study Plan。继续练习后再来复盘。",
                    ordered.size(),
                    levelChange,
                    List.of()
            );
        }

        List<Cluster> clusters = recurringClusters(ordered);

        if (clusters.isEmpty()) {
            return new LearningReviewResponse(
                    LearningReviewStatus.INSUFFICIENT_DATA,
                    "最近 " + ordered.size()
                            + " 次练习中没有发现反复出现的学习问题，暂时无法给出可靠的 Study Plan。",
                    ordered.size(),
                    levelChange,
                    List.of()
            );
        }

        List<StudyPlanRecommendation> plan = new ArrayList<>(clusters.size());
        for (int index = 0; index < clusters.size(); index++) {
            Cluster cluster = clusters.get(index);
            plan.add(new StudyPlanRecommendation(
                    index + 1,
                    cluster.category,
                    IssueCategoryLabels.label(cluster.category),
                    cluster.severity,
                    cluster.issues.size(),
                    cluster.attemptCount,
                    actionFor(cluster),
                    cluster.evidence(),
                    groundingMaterialsFor(cluster)
            ));
        }

        return new LearningReviewResponse(
                LearningReviewStatus.SUFFICIENT_DATA,
                "基于最近 " + ordered.size() + " 次 Practice Attempt，发现 " + plan.size()
                        + " 类反复出现的学习问题。",
                ordered.size(),
                levelChange,
                plan
        );
    }

    private static List<Cluster> recurringClusters(List<PracticeAttemptSnapshot> attempts) {
        Map<ClusterKey, Cluster> clusters = new LinkedHashMap<>();
        for (PracticeAttemptSnapshot attempt : attempts) {
            Set<ClusterKey> seenInAttempt = new LinkedHashSet<>();
            for (LearningIssueSnapshot issue : attempt.issues()) {
                ClusterKey key = new ClusterKey(issue.category(), issue.severity());
                Cluster cluster = clusters.computeIfAbsent(key, ignored -> new Cluster(key));
                cluster.issues.add(new IssueRef(attempt, issue));
                if (seenInAttempt.add(key)) {
                    cluster.attemptCount++;
                }
            }
        }

        return clusters.values().stream()
                .filter(cluster -> cluster.attemptCount >= MIN_RECURRING_ATTEMPTS)
                .sorted(Comparator
                        .comparingInt(Cluster::priorityScore).reversed()
                        .thenComparingInt(cluster -> cluster.severity == IssueSeverity.MAJOR ? 0 : 1)
                        .thenComparing(Comparator.comparingInt((Cluster cluster) -> cluster.issues.size())
                                .reversed())
                        .thenComparingInt(cluster -> cluster.category.ordinal()))
                .toList();
    }

    private static String actionFor(Cluster cluster) {
        String severityLabel = cluster.severity == IssueSeverity.MAJOR ? "严重" : "轻微";
        return "优先复习「" + IssueCategoryLabels.label(cluster.category) + "」类学习问题（"
                + severityLabel + "）。先阅读下方依据材料，再用最近一次的原句做最小改写练习。";
    }

    private List<GroundingMaterial> groundingMaterialsFor(Cluster cluster) {
        String queryText = cluster.issues.stream()
                .map(ref -> String.join("\n",
                        ref.issue.originalSentence(), ref.issue.suggestion(), ref.issue.explanation()))
                .collect(Collectors.joining("\n"));
        try {
            return materialRetriever.groundingMaterials(List.of(cluster.category), queryText).stream()
                    .map(RankedGroundingMaterial::material)
                    .toList();
        } catch (RuntimeException exception) {
            // A retrieval outage must not hide a finding the workflow already made from the
            // learner's own history.
            log.warn("学习复盘依据材料检索失败，本次建议不引用依据: category={}, cause={}",
                    cluster.category, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private static EstimatedLevelChange estimatedLevelChange(List<PracticeAttemptSnapshot> ordered) {
        if (ordered.size() < MIN_LEVEL_TREND_ATTEMPTS) {
            return null;
        }
        List<EstimatedLevelPoint> points = ordered.stream()
                .map(attempt -> new EstimatedLevelPoint(
                        attempt.practiceAttemptId(), attempt.estimatedLevel(), attempt.createdAt()))
                .toList();
        EstimatedLevel from = points.getFirst().level();
        EstimatedLevel to = points.getLast().level();
        int delta = to.ordinal() - from.ordinal();
        LevelTrendDirection direction = delta > 0
                ? LevelTrendDirection.IMPROVING
                : delta < 0 ? LevelTrendDirection.WORSENING : LevelTrendDirection.STABLE;
        return new EstimatedLevelChange(from, to, delta, direction, points);
    }

    private record ClusterKey(IssueCategory category, IssueSeverity severity) {
    }

    private record IssueRef(PracticeAttemptSnapshot attempt, LearningIssueSnapshot issue) {
    }

    private static final class Cluster {

        private final IssueCategory category;
        private final IssueSeverity severity;
        private final List<IssueRef> issues = new ArrayList<>();
        private int attemptCount;

        private Cluster(ClusterKey key) {
            this.category = key.category();
            this.severity = key.severity();
        }

        private int priorityScore() {
            int severityBonus = severity == IssueSeverity.MAJOR ? MAJOR_BONUS : 0;
            return attemptCount * ATTEMPT_WEIGHT + issues.size() * OCCURRENCE_WEIGHT + severityBonus;
        }

        private List<LearningIssueEvidence> evidence() {
            return issues.stream()
                    .map(ref -> new LearningIssueEvidence(
                            ref.issue.learningIssueId(),
                            ref.attempt.practiceAttemptId(),
                            ref.attempt.createdAt(),
                            ref.issue.originalSentence(),
                            ref.issue.suggestion(),
                            ref.issue.explanation()))
                    .toList();
        }
    }
}
