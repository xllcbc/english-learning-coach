import type { ExplanationStatus } from '../types';

interface ExplanationViewProps {
  status: ExplanationStatus;
  text: string;
  error: string | null;
  onRetry: () => void;
}

export function ExplanationView({ status, text, error, onRetry }: ExplanationViewProps) {
  if (status === 'idle') {
    return null;
  }

  const isStreaming = status === 'streaming';

  return (
    <section className="explanation-card" aria-labelledby="explanation-title">
      <div className="section-title-row">
        <h3 id="explanation-title">
          Teacher Explanation <span className="section-hint">教师讲解</span>
        </h3>
        {isStreaming && (
          <span className="stream-pill" role="status">
            <span className="stream-dot" aria-hidden="true" />
            生成中…
          </span>
        )}
      </div>

      {status === 'error' ? (
        <div className="explanation-error" role="alert">
          <p className="explanation-error-message">{error ?? '教师讲解生成失败，请重试。'}</p>
          <p className="explanation-error-note">
            结构化反馈已经保存，重新生成讲解不会影响 Correction 与 Natural Revision。
          </p>
          <button type="button" className="retry-button" onClick={onRetry}>
            重试生成讲解
          </button>
        </div>
      ) : (
        <div
          className={isStreaming ? 'explanation-text is-streaming' : 'explanation-text'}
          aria-live="polite"
          aria-busy={isStreaming}
        >
          {text || '正在准备教师讲解…'}
        </div>
      )}
    </section>
  );
}
