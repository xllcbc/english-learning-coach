import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  ApiRequestError,
  createPractice,
  deleteSavedVocabulary,
  listSavedVocabulary,
  saveVocabulary,
  streamExplanation,
  toErrorMessage,
} from './api';
import { ExplanationView } from './components/ExplanationView';
import { FeedbackView } from './components/FeedbackView';
import { SavedVocabularyPanel } from './components/SavedVocabularyPanel';
import type { SavedVocabularyDeleteError } from './components/SavedVocabularyPanel';
import type { ExplanationStatus, PracticeResponse, SavedVocabularyEntry } from './types';
import { countWords, MAX_WORDS, MIN_WORDS } from './wordCount';

type RequestStatus = 'idle' | 'submitting' | 'error';
type CountState = 'empty' | 'valid' | 'invalid';

function countState(wordCount: number): CountState {
  if (wordCount === 0) {
    return 'empty';
  }
  if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
    return 'invalid';
  }
  return 'valid';
}

function countMessage(wordCount: number): string {
  if (wordCount === 0) {
    return `请输入 ${MIN_WORDS}-${MAX_WORDS} 词的英文写作。`;
  }
  if (wordCount < MIN_WORDS) {
    return `还差 ${MIN_WORDS - wordCount} 词达到最低要求。`;
  }
  if (wordCount > MAX_WORDS) {
    return `超出上限 ${wordCount - MAX_WORDS} 词，请删减后再提交。`;
  }
  return `词数符合要求，可以提交。`;
}

function formatTimestamp(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

export default function App() {
  const [writingText, setWritingText] = useState('');
  const [result, setResult] = useState<PracticeResponse | null>(null);
  const [status, setStatus] = useState<RequestStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [explanation, setExplanation] = useState('');
  const [explanationStatus, setExplanationStatus] = useState<ExplanationStatus>('idle');
  const [explanationError, setExplanationError] = useState<string | null>(null);
  const explanationAbort = useRef<AbortController | null>(null);
  const [savedVocabulary, setSavedVocabulary] = useState<SavedVocabularyEntry[]>([]);
  const [savedStatus, setSavedStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [savedError, setSavedError] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<SavedVocabularyDeleteError | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const wordCount = countWords(writingText);
  const currentCountState = countState(wordCount);
  const canSubmit = status !== 'submitting' && currentCountState === 'valid';
  const isMock = result?.mockMode ?? true;
  const savedWords = new Set(savedVocabulary.map((entry) => entry.word.toLowerCase()));

  const loadSavedVocabulary = useCallback(async () => {
    setSavedStatus('loading');
    setSavedError(null);
    setDeleteError(null);
    try {
      const entries = await listSavedVocabulary();
      setSavedVocabulary(entries);
      setSavedStatus('ready');
    } catch (error) {
      setSavedStatus('error');
      setSavedError(toErrorMessage(error));
    }
  }, []);

  useEffect(() => {
    void loadSavedVocabulary();
  }, [loadSavedVocabulary]);

  useEffect(() => {
    return () => {
      explanationAbort.current?.abort();
    };
  }, []);

  function cancelExplanation() {
    explanationAbort.current?.abort();
    explanationAbort.current = null;
  }

  function resetExplanation(status: ExplanationStatus) {
    setExplanation('');
    setExplanationError(null);
    setExplanationStatus(status);
  }

  async function startExplanation(practiceId: string) {
    cancelExplanation();

    const controller = new AbortController();
    explanationAbort.current = controller;
    resetExplanation('streaming');

    try {
      await streamExplanation(
        practiceId,
        {
          onStart: () => {
            if (!controller.signal.aborted) {
              setExplanationStatus('streaming');
            }
          },
          onDelta: (text) => {
            if (!controller.signal.aborted) {
              setExplanation((current) => current + text);
            }
          },
          onComplete: (fullText) => {
            if (!controller.signal.aborted) {
              setExplanation(fullText);
              setExplanationStatus('complete');
            }
          },
          onError: (message) => {
            if (!controller.signal.aborted) {
              setExplanationError(message);
              setExplanationStatus('error');
            }
          },
        },
        controller.signal,
      );
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      setExplanationError(
        error instanceof ApiRequestError
          ? error.message
          : '无法连接教师讲解服务，请重试。',
      );
      setExplanationStatus('error');
    } finally {
      if (explanationAbort.current === controller) {
        explanationAbort.current = null;
      }
    }
  }

  function handleRetryExplanation() {
    if (result) {
      void startExplanation(result.id);
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) {
      return;
    }

    cancelExplanation();
    setStatus('submitting');
    setErrorMessage(null);
    setResult(null);
    resetExplanation('idle');

    try {
      const practice = await createPractice(writingText);
      setResult(practice);
      setStatus('idle');
      void startExplanation(practice.id);
    } catch (error) {
      setStatus('error');
      setErrorMessage(toErrorMessage(error));
    }
  }

  async function handleSaveVocabulary(practiceAttemptId: string, word: string): Promise<void> {
    try {
      const saved = await saveVocabulary(practiceAttemptId, word);
      setSavedVocabulary((current) => [
        saved,
        ...current.filter((entry) => entry.id !== saved.id),
      ]);
      setSavedStatus('ready');
      setSavedError(null);
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === 'VOCABULARY_ALREADY_SAVED') {
        void loadSavedVocabulary();
      }
      throw error;
    }
  }

  async function handleDeleteSavedVocabulary(id: string): Promise<void> {
    setDeletingId(id);
    setDeleteError(null);
    try {
      await deleteSavedVocabulary(id);
      setSavedVocabulary((current) => current.filter((entry) => entry.id !== id));
    } catch (error) {
      setDeleteError({ id, message: toErrorMessage(error) });
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            W
          </span>
          <div>
            <h1>WriteLab</h1>
            <p>英语写作教练</p>
          </div>
        </div>
        <span
          className={`mode-pill ${isMock ? 'mode-mock' : 'mode-real'}`}
          title={isMock ? '反馈由确定性示例适配器生成' : '反馈由真实模型生成'}
        >
          <span className="mode-dot" aria-hidden="true" />
          {isMock ? 'Mock 模式' : '真实模型'}
        </span>
      </header>

      {isMock && (
        <div className="mock-banner" role="note">
          <strong>Mock 模式</strong>
          <span>当前反馈由确定性示例适配器生成，用于验证完整流程，不代表真实模型质量。</span>
        </div>
      )}

      <main className="workspace">
        <section className="panel editor-panel" aria-labelledby="editor-title">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Writing Practice</p>
              <h2 id="editor-title">写作练习</h2>
            </div>
            <span className={`count-pill count-${currentCountState}`}>{wordCount} 词</span>
          </div>

          <form onSubmit={handleSubmit} noValidate>
            <label className="sr-only" htmlFor="writing-text">
              英文写作内容
            </label>
            <textarea
              id="writing-text"
              className="writing-input"
              value={writingText}
              onChange={(event) => setWritingText(event.target.value)}
              placeholder="在这里输入 200-500 词的英文写作…"
              rows={14}
              spellCheck
            />

            <p className={`count-hint count-${currentCountState}`} aria-live="polite">
              {countMessage(wordCount)}
            </p>

            {errorMessage && (
              <div className="error-banner" role="alert">
                {errorMessage}
              </div>
            )}

            <div className="editor-actions">
              <span className="privacy-note">内容仅保存在本地数据库。</span>
              <button type="submit" className="submit-button" disabled={!canSubmit}>
                {status === 'submitting' ? '正在生成反馈…' : '提交写作练习'}
              </button>
            </div>
          </form>
        </section>

        <section className="panel feedback-panel" aria-labelledby="feedback-title">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Structured Feedback</p>
              <h2 id="feedback-title">结构化反馈</h2>
            </div>
            {result && (
              <span className="result-meta">
                {result.wordCount} 词 · {formatTimestamp(result.createdAt)}
              </span>
            )}
          </div>

          {status === 'submitting' && (
            <div className="loading-state" role="status">
              <span className="spinner" aria-hidden="true" />
              <h3>正在生成结构化反馈…</h3>
              <p>Mock 适配器会立即完成，真实模型可能需要几秒钟。</p>
            </div>
          )}

          {status !== 'submitting' && !result && (
            <div className="empty-state">
              <span className="empty-icon" aria-hidden="true">
                ✎
              </span>
              <h3>还没有反馈</h3>
              <p>
                提交一篇 200-500 词的英文写作后，这里会展示纠错稿、自然改写、估计等级、评分、优点、学习问题和词汇建议。
              </p>
            </div>
          )}

          {status !== 'submitting' && result && (
            <FeedbackView
              result={result}
              savedWords={savedWords}
              onSaveVocabulary={handleSaveVocabulary}
            >
              <ExplanationView
                status={explanationStatus}
                text={explanation}
                error={explanationError}
                onRetry={handleRetryExplanation}
              />
            </FeedbackView>
          )}
        </section>
      </main>

      <SavedVocabularyPanel
        entries={savedVocabulary}
        status={savedStatus}
        error={savedError}
        deleteError={deleteError}
        deletingId={deletingId}
        onRetry={loadSavedVocabulary}
        onDelete={handleDeleteSavedVocabulary}
      />
    </div>
  );
}
