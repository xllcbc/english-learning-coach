import type { ReactNode } from 'react';
import { categoryLabels, levelDescriptions, scoreLabels, severityLabels } from '../labels';
import type { PracticeResponse, ToolInvocation, ToolInvocationStatus } from '../types';
import { VocabularySection } from './VocabularySection';

const toolStatusLabels: Record<ToolInvocationStatus, string> = {
  SUCCESS: '成功',
  NOT_FOUND: '未找到',
  BLOCKED: '超出上限',
  FAILED: '失败',
};

function ToolTrace({ invocations }: { invocations: ToolInvocation[] }) {
  return (
    <section className="feedback-section" aria-labelledby="tool-trace-title">
      <details className="tool-trace">
        <summary id="tool-trace-title">
          <span className="tool-trace-heading">Tool Invocation 轨迹</span>
          <span className="count-badge">{invocations.length} 次</span>
        </summary>

        {invocations.length === 0 ? (
          <p className="muted tool-trace-empty">本次反馈没有自动查词。</p>
        ) : (
          <ol className="tool-invocation-list">
            {invocations.map((invocation, index) => (
              <li className="tool-invocation" key={`${invocation.toolName}-${index}`}>
                <span className="tool-name">{invocation.toolName}</span>
                <span className="tool-word">{invocation.arguments.word ?? '—'}</span>
                <span className={`tool-status tool-status-${invocation.status.toLowerCase()}`}>
                  {toolStatusLabels[invocation.status]}
                </span>
                <span className="tool-duration">{invocation.durationMillis} ms</span>
              </li>
            ))}
          </ol>
        )}

        <p className="tool-note">自动查词有次数上限，结果仅用于整理词汇建议，不会自动保存。</p>
      </details>
    </section>
  );
}

function ScoreMeter({ label, value }: { label: string; value: number }) {
  return (
    <div className="score-row">
      <div className="score-heading">
        <span>{label}</span>
        <strong>
          {value}
          <small>/5</small>
        </strong>
      </div>
      <div
        className="score-track"
        role="progressbar"
        aria-label={`${label}评分`}
        aria-valuemin={1}
        aria-valuemax={5}
        aria-valuenow={value}
      >
        {[1, 2, 3, 4, 5].map((segment) => (
          <span
            key={segment}
            className={segment <= value ? 'score-segment is-filled' : 'score-segment'}
          />
        ))}
      </div>
    </div>
  );
}

interface FeedbackViewProps {
  result: PracticeResponse;
  savedWords: ReadonlySet<string>;
  onSaveVocabulary: (practiceAttemptId: string, word: string) => Promise<void>;
  children?: ReactNode;
}

export function FeedbackView({
  result,
  savedWords,
  onSaveVocabulary,
  children,
}: FeedbackViewProps) {
  const { feedback } = result;
  const { scores, learningIssues } = feedback;

  return (
    <div className="feedback-content">
      <div className="overview-grid">
        <div className="level-card">
          <span className="card-label">Estimated Level</span>
          <div className="level-value">{feedback.estimatedLevel}</div>
          <span className="level-caption">{levelDescriptions[feedback.estimatedLevel]}</span>
          <p className="level-disclaimer">非权威估计，仅用于学习反馈。</p>
        </div>

        <div className="scores-card">
          <span className="card-label">三项评分</span>
          <ScoreMeter label={scoreLabels.grammar} value={scores.grammar} />
          <ScoreMeter label={scoreLabels.vocabulary} value={scores.vocabulary} />
          <ScoreMeter label={scoreLabels.coherence} value={scores.coherence} />
        </div>
      </div>

      {children}

      <section className="feedback-section" aria-labelledby="strengths-title">
        <div className="section-title-row">
          <h3 id="strengths-title">值得保留的优点</h3>
          <span className="count-badge">{feedback.strengths.length} 条</span>
        </div>
        <ul className="strength-list">
          {feedback.strengths.map((strength, index) => (
            <li key={index}>
              <span className="strength-index" aria-hidden="true">
                {String(index + 1).padStart(2, '0')}
              </span>
              <span>{strength}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="feedback-section" aria-labelledby="correction-title">
        <div className="section-title-row">
          <h3 id="correction-title">
            Correction <span className="section-hint">纠错稿</span>
          </h3>
        </div>
        <div className="text-block">{feedback.correction}</div>
      </section>

      <section className="feedback-section" aria-labelledby="revision-title">
        <div className="section-title-row">
          <h3 id="revision-title">
            Natural Revision <span className="section-hint">自然改写稿</span>
          </h3>
        </div>
        <div className="text-block">{feedback.naturalRevision}</div>
      </section>

      <section className="feedback-section" aria-labelledby="issues-title">
        <div className="section-title-row">
          <h3 id="issues-title">Learning Issue</h3>
          <span className="count-badge">{learningIssues.length} 条</span>
        </div>
        {learningIssues.length === 0 ? (
          <p className="muted">本次没有记录学习问题。</p>
        ) : (
          <div className="issue-list">
            {learningIssues.map((issue, index) => (
              <article className="issue-card" key={index}>
                <div className="issue-tags">
                  <span className="category-tag">{categoryLabels[issue.category]}</span>
                  <span className={`severity-tag severity-${issue.severity.toLowerCase()}`}>
                    {severityLabels[issue.severity]}
                  </span>
                </div>
                <dl className="issue-detail">
                  <div>
                    <dt>原句</dt>
                    <dd>{issue.originalSentence}</dd>
                  </div>
                  <div>
                    <dt>改为</dt>
                    <dd>{issue.suggestion}</dd>
                  </div>
                </dl>
                <p className="issue-explanation">{issue.explanation}</p>
              </article>
            ))}
          </div>
        )}
      </section>

      <VocabularySection
        result={result}
        savedWords={savedWords}
        onSaveVocabulary={onSaveVocabulary}
      />

      <ToolTrace invocations={result.toolInvocations} />
    </div>
  );
}
