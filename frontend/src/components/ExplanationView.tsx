import type { ExplanationStatus, GroundingCitation } from '../types';
import { categoryLabels } from '../labels';

interface ExplanationViewProps {
  status: ExplanationStatus;
  text: string;
  groundingMaterials: GroundingCitation[];
  error: string | null;
  replay: boolean;
  onGenerate: () => void;
}

export function ExplanationView({
  status,
  text,
  groundingMaterials,
  error,
  replay,
  onGenerate,
}: ExplanationViewProps) {
  const isStreaming = status === 'streaming';

  return (
    <section className="explanation-card" aria-labelledby="explanation-title">
      <div className="section-title-row">
        <h3 id="explanation-title">
          Teacher Explanation <span className="section-hint">教师讲解</span>
        </h3>
        {isStreaming && (
          <span className="stream-pill" role="status">
            {replay ? (
              '已保存'
            ) : (
              <>
                <span className="stream-dot" aria-hidden="true" />
                生成中…
              </>
            )}
          </span>
        )}
      </div>

      {status === 'idle' && (
        <div className="explanation-empty">
          <p className="explanation-empty-note">
            这条记录还没有保存的教师讲解。生成后会保存下来，下次回看可直接查看。
          </p>
          <button type="button" className="retry-button" onClick={onGenerate}>
            生成教师讲解
          </button>
        </div>
      )}

      {status === 'error' && (
        <div className="explanation-error" role="alert">
          <p className="explanation-error-message">{error ?? '教师讲解生成失败，请重试。'}</p>
          <p className="explanation-error-note">
            结构化反馈已经保存，重新生成讲解不会影响 Correction 与 Natural Revision。
          </p>
          <button type="button" className="retry-button" onClick={onGenerate}>
            重试生成讲解
          </button>
        </div>
      )}

      {(status === 'streaming' || status === 'complete') && (
        <>
          <div
            className={isStreaming ? 'explanation-text is-streaming' : 'explanation-text'}
            aria-live="polite"
            aria-busy={isStreaming}
          >
            {text || '正在准备教师讲解…'}
          </div>
          {status === 'complete' && groundingMaterials.length > 0 && (
            <div className="grounding-list" aria-labelledby="grounding-title">
              <h4 id="grounding-title">
                本次依据 <span className="section-hint">Grounding Material</span>
              </h4>
              <ul>
                {groundingMaterials.map((material) => (
                  <li key={material.reference} className="grounding-item">
                    <p className="grounding-heading">
                      <span className="grounding-reference">依据{material.reference}</span>
                      <span className="grounding-category">{categoryLabels[material.category]}</span>
                    </p>
                    <p className="grounding-content">{material.content}</p>
                    <p className="grounding-source">
                      {material.source} · {material.license}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </section>
  );
}
