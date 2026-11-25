import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';

import {
  getConversation,
  streamConversationTurn,
  toErrorMessage,
} from '../api';
import type {
  ConversationCitation,
  ConversationCitationKind,
  TeacherConversation,
  TeacherConversationTurn,
} from '../types';

interface TeacherConversationPanelProps {
  practiceId: string;
  defaultMockMode: boolean;
}

const citationKindLabels: Record<ConversationCitationKind, string> = {
  GROUNDING_MATERIAL: '依据材料',
  DICTIONARY_ENTRY: '词典条目',
  USER_DOCUMENT: '我的文档',
  LEARNING_ISSUE: '学习问题',
};

type LoadStatus = 'loading' | 'ready' | 'error';

export function TeacherConversationPanel({
  practiceId,
  defaultMockMode,
}: TeacherConversationPanelProps) {
  const [conversation, setConversation] = useState<TeacherConversation | null>(null);
  const [loadStatus, setLoadStatus] = useState<LoadStatus>('loading');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [pendingQuestion, setPendingQuestion] = useState<string | null>(null);
  const [streamedAnswer, setStreamedAnswer] = useState('');
  const [asking, setAsking] = useState(false);
  const [askError, setAskError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const loadConversation = useCallback(async () => {
    setLoadStatus('loading');
    setLoadError(null);
    try {
      const loaded = await getConversation(practiceId);
      setConversation(loaded);
      setLoadStatus('ready');
    } catch (error) {
      setLoadStatus('error');
      setLoadError(toErrorMessage(error));
    }
  }, [practiceId]);

  useEffect(() => {
    void loadConversation();
    return () => {
      abortRef.current?.abort();
    };
  }, [loadConversation]);

  const mockMode = conversation?.mockMode ?? defaultMockMode;
  const turns = conversation?.turns ?? [];

  function appendTurn(turn: TeacherConversationTurn) {
    setConversation((current) => {
      const base = current ?? {
        practiceAttemptId: practiceId,
        conversationId: null,
        modelMode: defaultMockMode ? 'MOCK' : 'REAL',
        mockMode: defaultMockMode,
        turns: [],
      };
      if (base.turns.some((existing) => existing.id === turn.id)) {
        return base;
      }
      return { ...base, turns: [...base.turns, turn] };
    });
  }

  async function handleAsk(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = draft.trim();
    if (!message || asking) {
      return;
    }

    const controller = new AbortController();
    abortRef.current = controller;
    setDraft('');
    setPendingQuestion(message);
    setStreamedAnswer('');
    setAskError(null);
    setAsking(true);

    try {
      await streamConversationTurn(
        practiceId,
        message,
        {
          onStart: (start) => {
            if (controller.signal.aborted) {
              return;
            }
            setConversation((current) => {
              if (!current) {
                return current;
              }
              return {
                ...current,
                conversationId: start.conversationId,
                mockMode: start.mockMode,
                modelMode: start.mockMode ? 'MOCK' : 'REAL',
              };
            });
          },
          onDelta: (text) => {
            if (!controller.signal.aborted) {
              setStreamedAnswer((current) => current + text);
            }
          },
          onComplete: (turn) => {
            if (controller.signal.aborted) {
              return;
            }
            appendTurn(turn);
            setPendingQuestion(null);
            setStreamedAnswer('');
          },
          onError: (messageText) => {
            if (!controller.signal.aborted) {
              setAskError(messageText);
              setPendingQuestion(null);
              setStreamedAnswer('');
            }
          },
        },
        controller.signal,
      );
    } catch (error) {
      if (!controller.signal.aborted) {
        setAskError(
          error instanceof Error
            ? toErrorMessage(error)
            : '无法连接教师对话服务，请重试。',
        );
        setPendingQuestion(null);
        setStreamedAnswer('');
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
      if (!controller.signal.aborted) {
        setAsking(false);
      }
    }
  }

  return (
    <section className="conversation-card" aria-labelledby="conversation-title">
      <div className="section-title-row">
        <h3 id="conversation-title">
          Teacher Conversation <span className="section-hint">教师对话</span>
        </h3>
        <span
          className={`mode-pill ${mockMode ? 'mode-mock' : 'mode-real'}`}
          title={mockMode ? '回答由确定性示例适配器生成' : '回答由真实模型生成'}
        >
          <span className="mode-dot" aria-hidden="true" />
          {mockMode ? 'Mock 模式' : '真实模型'}
        </span>
      </div>

      <p className="conversation-anchor">
        这个对话只属于当前 Practice Attempt。关闭后重开同一份练习会回放已保存的回合，不会重新生成。
      </p>

      {loadStatus === 'loading' && (
        <p className="muted" role="status">
          正在回放教师对话…
        </p>
      )}

      {loadStatus === 'error' && (
        <div className="panel-error" role="alert">
          <p>{loadError ?? '加载教师对话失败，请稍后重试。'}</p>
          <button type="button" className="text-button" onClick={() => void loadConversation()}>
            重新加载
          </button>
        </div>
      )}

      {loadStatus === 'ready' && turns.length === 0 && !pendingQuestion && (
        <div className="conversation-empty">
          <p>还没有对话。可以就这篇写作的用词、句式或学习问题继续追问。</p>
        </div>
      )}

      {(turns.length > 0 || pendingQuestion) && (
        <ol className="conversation-turns" aria-label="教师对话回合">
          {turns.map((turn) => (
            <ConversationTurn key={turn.id} turn={turn} />
          ))}
          {pendingQuestion && (
            <li className="conversation-turn">
              <p className="conversation-question">{pendingQuestion}</p>
              <div
                className="conversation-answer is-streaming"
                aria-live="polite"
                aria-busy={asking}
              >
                {streamedAnswer || '正在思考…'}
              </div>
            </li>
          )}
        </ol>
      )}

      {askError && (
        <div className="panel-error" role="alert">
          <p>{askError}</p>
        </div>
      )}

      <form className="conversation-form" onSubmit={handleAsk}>
        <label className="sr-only" htmlFor="conversation-input">
          就这份 Practice Attempt 追问
        </label>
        <textarea
          id="conversation-input"
          className="conversation-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="就这篇写作继续追问，例如：这里为什么用 although？"
          rows={3}
          disabled={asking || loadStatus !== 'ready'}
        />
        <div className="conversation-actions">
          <span className="privacy-note">回答与引用会随这份练习保存。</span>
          <button
            type="submit"
            className="retry-button"
            disabled={asking || loadStatus !== 'ready' || draft.trim().length === 0}
          >
            {asking ? '正在回答…' : '提问'}
          </button>
        </div>
      </form>
    </section>
  );
}

function ConversationTurn({ turn }: { turn: TeacherConversationTurn }) {
  const mockTurn = turn.modelMode === 'MOCK';
  return (
    <li className="conversation-turn">
      <div className="conversation-question-row">
        <p className="conversation-question">{turn.learnerMessage}</p>
        <span
          className={`turn-mode ${mockTurn ? 'turn-mode-mock' : 'turn-mode-real'}`}
          title={mockTurn ? '这一回合由确定性示例适配器生成' : '这一回合由真实模型生成'}
        >
          {mockTurn ? 'Mock 生成' : '真实生成'}
        </span>
      </div>
      <div className="conversation-answer">{turn.answer}</div>
      {turn.citations.length > 0 && (
        <div className="grounding-list conversation-citations" aria-label="本次回答的依据">
          <h4>本次回答的依据</h4>
          <ul>
            {turn.citations.map((citation) => (
              <CitationItem key={`${turn.id}-${citation.reference}`} citation={citation} />
            ))}
          </ul>
        </div>
      )}
    </li>
  );
}

function CitationItem({ citation }: { citation: ConversationCitation }) {
  return (
    <li className="grounding-item">
      <p className="grounding-heading">
        <span className="grounding-reference">依据{citation.reference}</span>
        <span className="grounding-kind">{citationKindLabels[citation.kind]}</span>
      </p>
      <p className="grounding-title">{citation.title}</p>
      <p className="grounding-content">{citation.content}</p>
      <p className="grounding-source">
        {citation.source}
        {citation.license ? ` · ${citation.license}` : ''}
      </p>
    </li>
  );
}
