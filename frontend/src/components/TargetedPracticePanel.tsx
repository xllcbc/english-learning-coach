import { formatTimestamp } from '../format';
import { severityLabels } from '../labels';
import type { TargetedPractice, TargetedPracticeTrend } from '../types';

const trendLabels: Record<TargetedPracticeTrend, string> = {
  IMPROVED: '改善',
  UNCHANGED: '持平',
  WORSENED: '退步',
};

interface TargetedPracticePanelProps {
  practice: TargetedPractice;
  onDismiss?: () => void;
}

/**
 * The task card shown before submission and the before/after comparison shown afterwards. It is
 * deliberately separate from FeedbackView: grading feedback keeps its existing shape, and the
 * targeted issue's movement is an additional view over that same Practice Attempt.
 */
export function TargetedPracticePanel({ practice, onDismiss }: TargetedPracticePanelProps) {
  return (
    <section className="panel targeted-practice-panel" aria-labelledby="targeted-practice-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Targeted Practice</p>
          <h2 id="targeted-practice-title">针对性练习</h2>
        </div>
        <div className="targeted-heading-meta">
          <span className="priority-pill">优先 {practice.priority}</span>
          <span className={`severity-pill severity-${practice.severity.toLowerCase()}`}>
            {severityLabels[practice.severity]}
          </span>
          {onDismiss && (
            <button type="button" className="text-button" onClick={onDismiss}>
              取消针对性练习
            </button>
          )}
        </div>
      </div>

      <div className="targeted-focus">
        <span className="category-tag">{practice.categoryLabel}</span>
        <p>{practice.focus}</p>
      </div>

      {practice.status === 'PENDING' ? (
        <>
          <div className="targeted-prompt" aria-label="针对性练习写作任务">
            <h3>写作任务</h3>
            <p>{practice.prompt}</p>
          </div>
          <p className="targeted-before muted">
            练前：最近 {practice.attemptCount} 次练习中出现 {practice.occurrenceCount} 次；最近一次出现{' '}
            {practice.beforeCount} 次。
          </p>
          {practice.evidence.length > 0 && (
            <details className="targeted-evidence">
              <summary>查看这次任务依据的 Learning Issue</summary>
              <ul>
                {practice.evidence.map((evidence) => (
                  <li key={evidence.learningIssueId}>
                    <p className="evidence-sentence">{evidence.originalSentence}</p>
                    <p className="evidence-explanation">{evidence.explanation}</p>
                  </li>
                ))}
              </ul>
            </details>
          )}
          {practice.groundingMaterials.length > 0 && (
            <details className="targeted-evidence">
              <summary>查看这次任务的依据材料</summary>
              <ul>
                {practice.groundingMaterials.map((material) => (
                  <li key={`${material.category}-${material.content}`}>
                    <p className="grounding-content">{material.content}</p>
                    <p className="grounding-source">
                      {material.source} · {material.license}
                    </p>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      ) : (
        practice.comparison && <Comparison practice={practice} />
      )}
    </section>
  );
}

function Comparison({ practice }: { practice: TargetedPractice }) {
  const comparison = practice.comparison;
  if (!comparison) {
    return null;
  }

  return (
    <div className="targeted-comparison" aria-label="针对性问题练前练后对比">
      <h3>练前 / 练后</h3>
      <div className="targeted-comparison-values">
        <div className="targeted-count">
          <span>练前</span>
          <strong>{comparison.beforeCount} 次</strong>
          <span className="muted">{formatTimestamp(comparison.beforeCreatedAt)}</span>
        </div>
        <span className="targeted-arrow" aria-hidden="true">
          →
        </span>
        <div className="targeted-count">
          <span>练后</span>
          <strong>{comparison.afterCount} 次</strong>
          <span className="muted">{formatTimestamp(comparison.afterCreatedAt)}</span>
        </div>
        <span className={`trend-pill trend-${comparison.trend.toLowerCase()}`}>
          {trendLabels[comparison.trend]}
        </span>
      </div>
      <p className="targeted-change">
        {comparison.change > 0
          ? `针对性问题减少 ${comparison.change} 次。`
          : comparison.change < 0
            ? `针对性问题增加 ${Math.abs(comparison.change)} 次，建议继续练习这一类问题。`
            : '针对性问题次数没有变化，建议回看依据材料后再练一次。'}
      </p>
    </div>
  );
}
