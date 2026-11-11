import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  ApiRequestError,
  createPractice,
  deleteSavedVocabulary,
  getModelMode,
  getPractice,
  listPractices,
  listSavedVocabulary,
  saveVocabulary,
  streamExplanation,
  toErrorMessage,
} from './api';
import { ExplanationView } from './components/ExplanationView';
import { FeedbackView } from './components/FeedbackView';
import { HistoryPanel } from './components/HistoryPanel';
import type { HistoryStatus } from './components/HistoryPanel';
import { SavedVocabularyPanel } from './components/SavedVocabularyPanel';
import type { SavedVocabularyDeleteError } from './components/SavedVocabularyPanel';
import { formatTimestamp } from './format';
import type {
  ExplanationStatus,
  PracticeResponse,
  PracticeSummary,
  SavedVocabularyEntry,
} from './types';
import { countWords, MAX_WORDS, MIN_WORDS } from './wordCount';

type RequestStatus = 'idle' | 'submitting' | 'error';
type DetailStatus = 'idle' | 'loading' | 'error';
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

export default function App() {
  const [writingText, setWritingText] = useState('');
  const [result, setResult] = useState<PracticeResponse | null>(null);
  const [serverMockMode, setServerMockMode] = useState<boolean | null>(null);
  const [status, setStatus] = useState<RequestStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [explanation, setExplanation] = useState('');
  const [explanationStatus, setExplanationStatus] = useState<ExplanationStatus>('idle');
  const [explanationError, setExplanationError] = useState<string | null>(null);
  const [explanationReplay, setExplanationReplay] = useState(false);
  const explanationAbort = useRef<AbortController | null>(null);
  const [savedVocabulary, setSavedVocabulary] = useState<SavedVocabularyEntry[]>([]);
  const [savedStatus, setSavedStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [savedError, setSavedError] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<SavedVocabularyDeleteError | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const [history, setHistory] = useState<PracticeSummary[]>([]);
  const [historyStatus, setHistoryStatus] = useState<HistoryStatus>('loading');
  const [historyCollapsed, setHistoryCollapsed] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailStatus, setDetailStatus] = useState<DetailStatus>('idle');
  const [detailError, setDetailError] = useState<string | null>(null);
  const detailRequest = useRef(0);
  const feedbackHeadingRef = useRef<HTMLHeadingElement>(null);

  const wordCount = countWords(writingText);
  const currentCountState = countState(wordCount);
  const canSubmit = status !== 'submitting' && currentCountState === 'valid';
  const isMock = result?.mockMode ?? serverMockMode ?? true;
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
    let active = true;
    getModelMode()
      .then((info) => {
        if (active) {
          setServerMockMode(info.mockMode);
        }
      })
      .catch(() => {
        // Fall back to the result-derived mode if the endpoint is unavailable.
      });
    return () => {
      active = false;
    };
  }, []);

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
    setExplanationReplay(false);
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
          onStart: (replay) => {
            if (!controller.signal.aborted) {
              setExplanationReplay(replay);
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

  function handleGenerateExplanation() {
    if (result) {
      void startExplanation(result.id);
    }
  }

  async function refreshHistory() {
    try {
      const attempts = await listPractices();
      setHistory(attempts);
      setHistoryStatus('ready');
    } catch {
      setHistoryStatus('error');
    }
  }

  useEffect(() => {
    setHistoryStatus('loading');
    void refreshHistory();
  }, []);

  async function handleHistoryRetry() {
    setHistoryStatus('loading');
    await refreshHistory();
  }

  async function handleSelect(id: string) {
    const requestId = detailRequest.current + 1;
    detailRequest.current = requestId;

    cancelExplanation();
    resetExplanation('idle');
    setSelectedId(id);
    setDetailStatus('loading');
    setDetailError(null);
    setErrorMessage(null);

    try {
      const practice = await getPractice(id);
      if (requestId !== detailRequest.current) {
        return;
      }
      setResult(practice);
      setWritingText(practice.writingText);
      setStatus('idle');
      setDetailStatus('idle');
      setHistoryCollapsed(true);
      if (practice.hasExplanation) {
        void startExplanation(practice.id);
      }
      feedbackHeadingRef.current?.focus();
    } catch (error) {
      if (requestId !== detailRequest.current) {
        return;
      }
      setDetailStatus('error');
      setDetailError(
        error instanceof ApiRequestError
          ? error.message
          : '无法加载该练习记录，请确认服务已启动。',
      );
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
    setDetailStatus('idle');
    setDetailError(null);

    try {
      const practice = await createPractice(writingText);
      setResult(practice);
      setSelectedId(practice.id);
      setStatus('idle');
      void startExplanation(practice.id);
      void refreshHistory();
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
        <HistoryPanel
          summaries={history}
          status={historyStatus}
          selectedId={selectedId}
          collapsed={historyCollapsed}
          onSelect={handleSelect}
          onRetry={handleHistoryRetry}
          onToggle={() => setHistoryCollapsed((collapsed) => !collapsed)}
        />

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
              <h2 id="feedback-title" ref={feedbackHeadingRef} tabIndex={-1}>
                结构化反馈
              </h2>
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

          {status !== 'submitting' && detailStatus === 'loading' && (
            <div className="loading-state" role="status">
              <span className="spinner" aria-hidden="true" />
              <h3>正在加载这次练习记录…</h3>
              <p>正在读取已保存的完整反馈。</p>
            </div>
          )}

          {status !== 'submitting' && detailStatus === 'error' && (
            <div className="error-banner detail-error" role="alert">
              <p>{detailError}</p>
              {selectedId && (
                <button type="button" className="inline-retry" onClick={() => handleSelect(selectedId)}>
                  重新加载
                </button>
              )}
            </div>
          )}

          {status !== 'submitting' && detailStatus === 'idle' && !result && (
            <div className="empty-state">
              <span className="empty-icon" aria-hidden="true">
                ✎
              </span>
              <h3>还没有反馈</h3>
              <p>
                提交一篇 200-500 词的英文写作后，这里会展示纠错稿、自然改写、估计等级、评分、优点、学习问题和词汇建议。也可以从练习历史回放过去的 Practice Attempt。
              </p>
            </div>
          )}

          {status !== 'submitting' && detailStatus !== 'loading' && result && (
            <FeedbackView
              result={result}
              savedWords={savedWords}
              onSaveVocabulary={handleSaveVocabulary}
            >
              <ExplanationView
                status={explanationStatus}
                text={explanation}
                error={explanationError}
                replay={explanationReplay}
                onGenerate={handleGenerateExplanation}
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
