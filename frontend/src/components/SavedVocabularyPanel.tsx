import { useState } from 'react';
import type { SavedVocabularyEntry } from '../types';

export interface SavedVocabularyDeleteError {
  id: string;
  message: string;
}

interface SavedVocabularyPanelProps {
  entries: SavedVocabularyEntry[];
  status: 'loading' | 'ready' | 'error';
  error: string | null;
  deleteError: SavedVocabularyDeleteError | null;
  deletingId: string | null;
  onRetry: () => void;
  onDelete: (id: string) => void;
}

export function SavedVocabularyPanel({
  entries,
  status,
  error,
  deleteError,
  deletingId,
  onRetry,
  onDelete,
}: SavedVocabularyPanelProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = entries.find((entry) => entry.id === selectedId) ?? entries[0] ?? null;

  return (
    <section className="panel saved-vocabulary-panel" aria-labelledby="saved-vocabulary-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Saved Vocabulary</p>
          <h2 id="saved-vocabulary-title">已保存词汇</h2>
        </div>
        <span className="count-pill">{entries.length} 条</span>
      </div>

      {status === 'loading' && (
        <p className="muted" role="status">
          正在加载已保存词汇…
        </p>
      )}

      {status === 'error' && (
        <div className="panel-error" role="alert">
          <p>{error ?? '加载已保存词汇失败，请稍后重试。'}</p>
          <button type="button" className="text-button" onClick={onRetry}>
            重新加载
          </button>
        </div>
      )}

      {status === 'ready' && entries.length === 0 && (
        <div className="saved-empty">
          <h3>还没有 Saved Vocabulary</h3>
          <p>在 Vocabulary Suggestion 中点击单词查词，再点击保存，词汇会出现在这里。</p>
        </div>
      )}

      {entries.length > 0 && (
        <div className="saved-layout">
          <ul className="saved-list" aria-label="已保存词汇列表">
            {entries.map((entry) => {
              const isSelected = entry.id === selected?.id;
              return (
                <li key={entry.id}>
                  <button
                    type="button"
                    className={isSelected ? 'saved-list-item is-selected' : 'saved-list-item'}
                    onClick={() => setSelectedId(entry.id)}
                    aria-pressed={isSelected}
                  >
                    <span className="saved-word">{entry.word}</span>
                    <span className="saved-pos">{entry.partOfSpeech}</span>
                  </button>
                </li>
              );
            })}
          </ul>

          {selected && (
            <article className="saved-detail" aria-live="polite">
              <div className="saved-detail-heading">
                <div>
                  <h3>{selected.word}</h3>
                  {selected.phonetic && <p className="saved-phonetic">{selected.phonetic}</p>}
                </div>
                {selected.partOfSpeech && <span className="saved-pos-pill">{selected.partOfSpeech}</span>}
              </div>

              <dl className="saved-detail-grid">
                <div>
                  <dt>中文释义</dt>
                  <dd>{selected.meaningZh}</dd>
                </div>
                {selected.exampleEn && (
                  <div>
                    <dt>英文例句</dt>
                    <dd>{selected.exampleEn}</dd>
                  </div>
                )}
              </dl>

              {deleteError && deleteError.id === selected.id && (
                <div className="inline-error" role="alert">
                  {deleteError.message}
                </div>
              )}

              <button
                type="button"
                className="delete-button"
                onClick={() => onDelete(selected.id)}
                disabled={deletingId === selected.id}
              >
                {deletingId === selected.id ? '正在删除…' : `删除“${selected.word}”`}
              </button>
            </article>
          )}
        </div>
      )}
    </section>
  );
}
