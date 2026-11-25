import { useCallback, useEffect, useState } from 'react';

import { createTargetedPractice, getLearningReview, toErrorMessage } from '../api';
import { formatTimestamp } from '../format';
import { severityLabels } from '../labels';
import type {
  EstimatedLevelChange,
  GroundingMaterial,
  LearningIssueEvidence,
  LearningReview,
  LevelTrendDirection,
  StudyPlanRecommendation,
  TargetedPractice,
} from '../types';

const directionLabels: Record<LevelTrendDirection, string> = {
  IMPROVING: '提升',
  WORSENING: '下降',
  STABLE: '持平',
};

type LoadStatus = 'loading' | 'ready' | 'error';

interface LearningReviewPanelProps {
  onStartTargetedPractice?: (practice: TargetedPractice) => void;
}

export function LearningReviewPanel({ onStartTargetedPractice }: LearningReviewPanelProps) {
  const [review, setReview] = useState<LearningReview | null>(null);
  const [status, setStatus] = useState<LoadStatus>('loading');
  const [error, setError] = useState<string | null>(null);
  const [startingKey, setStartingKey] = useState<string | null>(null);
  const [startError, setStartError] = useState<string | null>(null);

  const loadReview = useCallback(async () => {
    setStatus('loading');
    setError(null);
    try {
      const loaded = await getLearningReview();
      setReview(loaded);
      setStatus('ready');
    } catch (loadError) {
      setStatus('error');
      setError(toErrorMessage(loadError));
    }
  }, []);

  useEffect(() => {
    void loadReview();
  }, [loadReview]);

  async function startTargetedPractice(item: StudyPlanRecommendation) {
    const key = `${item.category}-${item.severity}`;
    setStartingKey(key);
    setStartError(null);
    try {
      const practice = await createTargetedPractice(item.category, item.severity);
      onStartTargetedPractice?.(practice);
    } catch (startFailure) {
      setStartError(toErrorMessage(startFailure));
    } finally {
      setStartingKey(null);
    }
  }

  const sufficient = review?.status === 'SUFFICIENT_DATA';
  const hasPlan = sufficient && (review?.studyPlan.length ?? 0) > 0;

  return (
    <section className="panel learning-review-panel" aria-labelledby="learning-review-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Learning Review</p>
          <h2 id="learning-review-title">学习复盘</h2>
        </div>
        <button
          type="button"
          className="text-button"
          onClick={() => void loadReview()}
          disabled={status === 'loading'}
        >
          {status === 'loading' ? '正在复盘…' : '重新复盘'}
        </button>
      </div>

      <p className="muted">
        跨最近的 Practice Attempt 汇总反复出现的学习问题，排出 Study Plan，并给出 Estimated Level 变化。
      </p>

      {status === 'loading' && !review && (
        <p className="muted" role="status">
          正在汇总最近的练习…
        </p>
      )}

      {status === 'error' && (
        <div className="panel-error" role="alert">
          <p>{error ?? '生成学习复盘失败，请稍后重试。'}</p>
          <button type="button" className="text-button" onClick={() => void loadReview()}>
            重试
          </button>
        </div>
      )}

      {review && (
        <div className="learning-review-body" aria-live="polite">
          <p className="review-summary">{review.message}</p>

          {review.estimatedLevelChange && (
            <LevelChange change={review.estimatedLevelChange} />
          )}

          {!hasPlan && (
            <div className="review-empty">
              <h3>还没有足够的反复问题可以复盘</h3>
              <p>
                继续完成几篇 Practice Attempt，复盘会自动按类别和严重度聚类，并为每类问题检索依据材料。
              </p>
            </div>
          )}

          {hasPlan && (
            <ol className="study-plan" aria-label="Study Plan">
              {review.studyPlan.map((item) => (
                <StudyPlanItem
                  key={`${item.priority}-${item.category}-${item.severity}`}
                  item={item}
                  starting={startingKey === `${item.category}-${item.severity}`}
                  onStart={() => void startTargetedPractice(item)}
                />
              ))}
            </ol>
          )}

          {startError && (
            <div className="panel-error" role="alert">
              <p>{startError}</p>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function LevelChange({ change }: { change: EstimatedLevelChange }) {
  return (
    <div className="level-change" aria-label="Estimated Level 变化">
      <div className="level-change-heading">
        <span className="level-badge">{change.fromLevel}</span>
        <span aria-hidden="true">→</span>
        <span className="level-badge">{change.toLevel}</span>
        <span className={`trend-pill trend-${change.direction.toLowerCase()}`}>
          {directionLabels[change.direction]}
        </span>
      </div>
      <ol className="level-points" aria-label="Estimated Level 序列">
        {change.points.map((point) => (
          <li key={`${point.practiceAttemptId}-${point.createdAt}`}>
            <span className="level-point-value">{point.level}</span>
            <span className="muted">{formatTimestamp(point.createdAt)}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

function StudyPlanItem({
  item,
  starting,
  onStart,
}: {
  item: StudyPlanRecommendation;
  starting: boolean;
  onStart: () => void;
}) {
  return (
    <li className="study-plan-item" aria-label={`优先级 ${item.priority}：${item.categoryLabel}`}>
      <div className="study-plan-heading">
        <span className="priority-pill">优先 {item.priority}</span>
        <strong>{item.categoryLabel}</strong>
        <span className={`severity-pill severity-${item.severity.toLowerCase()}`}>
          {severityLabels[item.severity]}
        </span>
        <span className="muted">
          最近 {item.attemptCount} 次练习中出现 {item.occurrenceCount} 次
        </span>
        <button
          type="button"
          className="text-button targeted-start"
          aria-label={`对「${item.categoryLabel}」发起针对性练习`}
          onClick={onStart}
          disabled={starting}
        >
          {starting ? '正在生成…' : '针对性练习'}
        </button>
      </div>

      <p className="study-plan-action">{item.action}</p>

      <div className="issue-evidence" aria-label="建议依据的 Learning Issue">
        <h4>可追溯的 Learning Issue</h4>
        <ul>
          {item.evidence.map((evidence) => (
            <EvidenceItem key={evidence.learningIssueId} evidence={evidence} />
          ))}
        </ul>
      </div>

      {item.groundingMaterials.length > 0 && (
        <div className="review-grounding" aria-label="建议的依据材料">
          <h4>依据材料</h4>
          <ul>
            {item.groundingMaterials.map((material) => (
              <GroundingItem
                key={`${material.category}-${material.content}`}
                material={material}
              />
            ))}
          </ul>
        </div>
      )}
    </li>
  );
}

function EvidenceItem({ evidence }: { evidence: LearningIssueEvidence }) {
  return (
    <li className="evidence-item">
      <p className="evidence-meta">
        <span className="evidence-id">Learning Issue · {evidence.learningIssueId}</span>
        <span className="muted">{formatTimestamp(evidence.practiceCreatedAt)}</span>
      </p>
      <p className="evidence-sentence">{evidence.originalSentence}</p>
      <p className="evidence-explanation">{evidence.explanation}</p>
    </li>
  );
}

function GroundingItem({ material }: { material: GroundingMaterial }) {
  return (
    <li className="grounding-item">
      <p className="grounding-content">{material.content}</p>
      <p className="grounding-source">
        {material.source} · {material.license}
      </p>
    </li>
  );
}
