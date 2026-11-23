import type { DocumentCitation, ExplanationStatus, GroundingCitation } from '../types';
import { categoryLabels } from '../labels';

interface ExplanationViewProps {
  status: ExplanationStatus;
  text: string;
  groundingMaterials: GroundingCitation[];
  documentCitations: DocumentCitation[];
  error: string | null;
  replay: boolean;
  onGenerate: () => void;
}

type CitationItem =
  | { kind: 'grounding'; reference: number; material: GroundingCitation }
  | { kind: 'document'; reference: number; document: DocumentCitation };

export function ExplanationView({
  status,
  text,
  groundingMaterials,
  documentCitations,
  error,
  replay,
  onGenerate,
}: ExplanationViewProps) {
  const isStreaming = status === 'streaming';
  const citations: CitationItem[] = [
    ...groundingMaterials.map((material): CitationItem => ({
      kind: 'grounding',
      reference: material.reference,
      material,
    })),
    ...documentCitations.map((document): CitationItem => ({
      kind: 'document',
      reference: document.reference,
      document,
    })),
  ].sort((left, right) => left.reference - right.reference);

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
          {status === 'complete' && citations.length > 0 && (
            <div className="grounding-list" aria-labelledby="grounding-title">
              <h4 id="grounding-title">
                本次依据 <span className="section-hint">规则与我的文档</span>
              </h4>
              <ul>
                {citations.map((citation) =>
                  citation.kind === 'grounding' ? (
                    <li key={`grounding-${citation.reference}`} className="grounding-item">
                      <p className="grounding-heading">
                        <span className="grounding-reference">依据{citation.reference}</span>
                        <span className="grounding-category">
                          {categoryLabels[citation.material.category]}
                        </span>
                        <span className="grounding-kind">规则</span>
                      </p>
                      <p className="grounding-content">{citation.material.content}</p>
                      <p className="grounding-source">
                        {citation.material.source} · {citation.material.license}
                      </p>
                    </li>
                  ) : (
                    <li key={`document-${citation.reference}`} className="grounding-item">
                      <p className="grounding-heading">
                        <span className="grounding-reference">依据{citation.reference}</span>
                        <span className="grounding-kind">我的文档</span>
                        {citation.document.deleted && (
                          <span className="grounding-deleted">文档已删除</span>
                        )}
                      </p>
                      <p className="grounding-title">{citation.document.title}</p>
                      <p className="grounding-content">{citation.document.content}</p>
                    </li>
                  ),
                )}
              </ul>
            </div>
          )}
        </>
      )}
    </section>
  );
}
