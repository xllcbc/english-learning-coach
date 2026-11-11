import { useEffect, useState } from 'react';
import { lookupWord, toErrorMessage } from '../api';
import type { DictionaryEntry, PracticeResponse, VocabularySuggestion } from '../types';

type LookupState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'found'; entry: DictionaryEntry }
  | { status: 'error'; message: string };

type SaveState = 'idle' | 'saving' | 'error';

interface VocabularySuggestionCardProps {
  practiceAttemptId: string;
  suggestion: VocabularySuggestion;
  isSaved: boolean;
  onSaveVocabulary: (practiceAttemptId: string, word: string) => Promise<void>;
}

function VocabularySuggestionCard({
  practiceAttemptId,
  suggestion,
  isSaved,
  onSaveVocabulary,
}: VocabularySuggestionCardProps) {
  const [lookup, setLookup] = useState<LookupState>({ status: 'idle' });
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [saveError, setSaveError] = useState<string | null>(null);

  async function handleLookup() {
    setLookup({ status: 'loading' });
    try {
      const entry = await lookupWord(suggestion.word);
      setLookup({ status: 'found', entry });
    } catch (error) {
      setLookup({ status: 'error', message: toErrorMessage(error) });
    }
  }

  async function handleSave() {
    if (lookup.status !== 'found') {
      return;
    }
    setSaveState('saving');
    setSaveError(null);
    try {
      await onSaveVocabulary(practiceAttemptId, suggestion.word);
      setSaveState('idle');
    } catch (error) {
      setSaveState('error');
      setSaveError(toErrorMessage(error));
    }
  }

  const isSaving = saveState === 'saving';

  useEffect(() => {
    if (isSaved) {
      setSaveState('idle');
      setSaveError(null);
    }
  }, [isSaved]);

  return (
    <article className="vocabulary-card">
      <div className="vocabulary-card-heading">
        <button
          type="button"
          className="vocabulary-word-button"
          onClick={handleLookup}
          disabled={lookup.status === 'loading'}
        >
          {suggestion.word}
        </button>
        <span className="vocabulary-lookup-hint">
          {lookup.status === 'loading' ? '正在查词…' : '点击查词'}
        </span>
      </div>
      <p>{suggestion.explanation}</p>
      {suggestion.example && <p className="vocabulary-example">{suggestion.example}</p>}
      {suggestion.exampleSource && (
        <p className="vocabulary-example-source">
          来源：{suggestion.exampleSource.source} · {suggestion.exampleSource.license}
        </p>
      )}

      {lookup.status === 'error' && (
        <div className="inline-error" role="alert">
          {lookup.message}
          <button type="button" className="text-button" onClick={handleLookup}>
            重试
          </button>
        </div>
      )}

      {lookup.status === 'found' && (
        <div className="lookup-result" aria-live="polite">
          <dl className="lookup-grid">
            {lookup.entry.phonetic && (
              <div>
                <dt>音标</dt>
                <dd>{lookup.entry.phonetic}</dd>
              </div>
            )}
            {lookup.entry.partOfSpeech && (
              <div>
                <dt>词性</dt>
                <dd>{lookup.entry.partOfSpeech}</dd>
              </div>
            )}
            <div>
              <dt>中文释义</dt>
              <dd>{lookup.entry.meaningZh}</dd>
            </div>
            {lookup.entry.exampleEn && (
              <div className="lookup-example">
                <dt>英文例句</dt>
                <dd>{lookup.entry.exampleEn}</dd>
              </div>
            )}
          </dl>

          <div className="lookup-actions">
            <button
              type="button"
              className="save-button"
              onClick={handleSave}
              disabled={isSaved || isSaving}
            >
              {isSaved ? '已保存' : isSaving ? '正在保存…' : '保存到 Saved Vocabulary'}
            </button>
          </div>

          {saveState === 'error' && saveError && !isSaved && (
            <div className="inline-error" role="alert">
              {saveError}
            </div>
          )}
        </div>
      )}
    </article>
  );
}

interface VocabularySectionProps {
  result: PracticeResponse;
  savedWords: ReadonlySet<string>;
  onSaveVocabulary: (practiceAttemptId: string, word: string) => Promise<void>;
}

export function VocabularySection({
  result,
  savedWords,
  onSaveVocabulary,
}: VocabularySectionProps) {
  const suggestions = result.feedback.vocabularySuggestions;

  return (
    <section className="feedback-section" aria-labelledby="vocabulary-title">
      <div className="section-title-row">
        <h3 id="vocabulary-title">Vocabulary Suggestion</h3>
        <span className="count-badge">{suggestions.length} 条</span>
      </div>

      {suggestions.length === 0 ? (
        <p className="muted">本次没有词汇建议。</p>
      ) : (
        <div className="vocabulary-list">
          {suggestions.map((suggestion, index) => (
            <VocabularySuggestionCard
              key={`${suggestion.word}-${index}`}
              practiceAttemptId={result.id}
              suggestion={suggestion}
              isSaved={savedWords.has(suggestion.word.toLowerCase())}
              onSaveVocabulary={onSaveVocabulary}
            />
          ))}
        </div>
      )}

      <p className="vocabulary-note">
        点击词汇可手动查词；只有点击保存后，词汇才会加入 Saved Vocabulary。
      </p>
    </section>
  );
}
