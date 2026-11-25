import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';

import {
  getSpeakingLatency,
  getSpeakingSession,
  listSpeakingSessions,
  openSpeakingChannel,
  playSpeakingAudio,
  stopSpeakingAudio,
  toErrorMessage,
} from '../api';
import type { SpeakingChannelConnection, SpeakingChannelHandlers } from '../api';
import type {
  SpeakingAudioReply,
  SpeakingFallbackReason,
  SpeakingInputMode,
  SpeakingLatencyStats,
  SpeakingSession,
  SpeakingSessionSummary,
  SpeakingTextReply,
} from '../types';

interface SpeakingPracticePanelProps {
  defaultMockMode: boolean;
}

interface DisplayTurn {
  key: string;
  inputMode: SpeakingInputMode;
  transcript: string;
  reply: string;
  fallbackReason: SpeakingFallbackReason | null;
}

type LoadStatus = 'loading' | 'ready' | 'error';
type ChannelStatus = 'connecting' | 'ready' | 'closed' | 'error';

/**
 * ADR-0011 latency budget: if a reply has not started playing after this long, the panel must say
 * it is thinking instead of leaving the learner in silence.
 */
export const SPEAKING_THINKING_BUDGET_MS = 2500;

const fallbackLabels: Record<SpeakingFallbackReason, string> = {
  failed: '语音识别失败，已退回文字输入。',
  timeout: '语音识别超时，已退回文字输入。',
  synthesis_failed: '语音播放暂不可用，已显示文字回答。',
};

function displayTurn(
  key: string,
  inputMode: SpeakingInputMode,
  transcript: string,
  reply: string,
  fallbackReason: SpeakingFallbackReason | null,
): DisplayTurn {
  return { key, inputMode, transcript, reply, fallbackReason };
}

function replayTurns(session: SpeakingSession): DisplayTurn[] {
  return session.turns.map((turn) => displayTurn(
    turn.id,
    turn.inputMode,
    turn.transcript,
    turn.reply,
    turn.fallbackReason,
  ));
}

function formatSeconds(milliseconds: number): string {
  return `${(milliseconds / 1000).toFixed(1)} 秒`;
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result ?? '');
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

/**
 * Speaking Practice panel. Voice is the primary path, but both the microphone control and the text
 * box share one WebSocket and one Speaking Session, so a failed transcription never blocks the
 * learner (ADR-0011). Past sessions are replayed through the read-only API.
 */
export function SpeakingPracticePanel({ defaultMockMode }: SpeakingPracticePanelProps) {
  const [sessions, setSessions] = useState<SpeakingSessionSummary[]>([]);
  const [loadStatus, setLoadStatus] = useState<LoadStatus>('loading');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [mockMode, setMockMode] = useState(defaultMockMode);
  const [turns, setTurns] = useState<DisplayTurn[]>([]);
  const [fallbackReason, setFallbackReason] = useState<SpeakingFallbackReason | null>(null);
  const [fallbackNotice, setFallbackNotice] = useState<string | null>(null);
  const [latencyStats, setLatencyStats] = useState<SpeakingLatencyStats | null>(null);
  const [draft, setDraft] = useState('');
  const [thinking, setThinking] = useState(false);
  const [thinkingOverBudget, setThinkingOverBudget] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [channelStatus, setChannelStatus] = useState<ChannelStatus>('connecting');
  const [isRecording, setIsRecording] = useState(false);
  const [micError, setMicError] = useState<string | null>(null);

  const connectionRef = useRef<SpeakingChannelConnection | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const localTurnSequence = useRef(0);
  const suppressPlaybackRef = useRef(false);

  const rememberSession = useCallback((id: string | null) => {
    sessionIdRef.current = id;
    setSessionId(id);
  }, []);

  const refreshLatency = useCallback(async () => {
    try {
      setLatencyStats(await getSpeakingLatency());
    } catch {
      // The latency badge is observational; a failed lookup must not block speaking.
    }
  }, []);

  const appendTurn = useCallback((
    inputMode: SpeakingInputMode,
    transcript: string,
    reply: string,
    reason: SpeakingFallbackReason | null,
  ) => {
    localTurnSequence.current += 1;
    const key = `local-${localTurnSequence.current}`;
    setTurns((current) => [...current, displayTurn(key, inputMode, transcript, reply, reason)]);
  }, []);

  const loadSession = useCallback(async (id: string) => {
    try {
      const session = await getSpeakingSession(id);
      rememberSession(session.id);
      setMockMode(session.mockMode);
      setTurns(replayTurns(session));
      setFallbackReason(null);
      setFallbackNotice(null);
      setError(null);
    } catch (loadFailure) {
      setError(toErrorMessage(loadFailure));
    }
  }, [rememberSession]);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const summaries = await listSpeakingSessions();
        if (!active) {
          return;
        }
        setSessions(summaries);
        setLoadStatus('ready');
        if (summaries.length > 0) {
          await loadSession(summaries[0].id);
        }
      } catch (loadFailure) {
        if (active) {
          setLoadStatus('error');
          setLoadError(toErrorMessage(loadFailure));
        }
      }
    }
    void load();
    return () => {
      active = false;
    };
  }, [loadSession]);

  useEffect(() => {
    void refreshLatency();
  }, [refreshLatency]);

  useEffect(() => {
    if (!thinking) {
      setThinkingOverBudget(false);
      return undefined;
    }
    const timer = setTimeout(() => setThinkingOverBudget(true), SPEAKING_THINKING_BUDGET_MS);
    return () => clearTimeout(timer);
  }, [thinking]);

  useEffect(() => {
    const handlers: SpeakingChannelHandlers = {
      onOpen: () => setChannelStatus('ready'),
      onAudio: (reply: SpeakingAudioReply) => {
        rememberSession(reply.sessionId);
        setMockMode(reply.mockMode);
        setFallbackReason(null);
        setFallbackNotice(null);
        setError(null);
        setThinking(false);
        appendTurn(reply.inputMode, reply.transcript, reply.reply, null);
        if (!suppressPlaybackRef.current) {
          playSpeakingAudio(reply.audio, reply.mimeType);
        }
        void refreshLatency();
      },
      onText: (reply: SpeakingTextReply) => {
        rememberSession(reply.sessionId);
        setMockMode(reply.mockMode);
        setError(null);
        setThinking(false);
        setFallbackReason(reply.reason);
        const transcriptionFallback = reply.reason !== 'synthesis_failed';
        setFallbackNotice(transcriptionFallback ? reply.text : null);
        appendTurn(
          reply.inputMode,
          reply.transcript,
          transcriptionFallback ? '' : reply.text,
          reply.reason,
        );
        void refreshLatency();
      },
      onInterrupted: () => {
        // The server abandoned the reply in flight. Local playback was already stopped and
        // suppressed when the learner started speaking. This acknowledgement must not clear
        // `thinking` (it may already belong to a newer turn) nor re-enable playback (a reply that
        // was in transit may still arrive); sending the new turn re-enables playback.
      },
      onError: (channelError) => {
        setError(channelError.message);
        setThinking(false);
        setChannelStatus((current) => (current === 'connecting' ? 'error' : current));
      },
      onClose: () => {
        setChannelStatus('closed');
        setThinking(false);
      },
    };

    const connection = openSpeakingChannel(handlers);
    connectionRef.current = connection;
    return () => {
      connection.close();
      connectionRef.current = null;
      streamRef.current?.getTracks().forEach((track) => track.stop());
      stopSpeakingAudio();
    };
  }, [appendTurn, rememberSession, refreshLatency]);

  const connectionReady = channelStatus === 'ready';
  const canRecord = typeof navigator !== 'undefined'
    && Boolean(navigator.mediaDevices?.getUserMedia)
    && typeof MediaRecorder !== 'undefined';

  async function toggleRecording() {
    if (isRecording) {
      recorderRef.current?.stop();
      return;
    }
    if (!canRecord) {
      setMicError('当前浏览器无法录音，请使用文字输入。');
      return;
    }
    if (!connectionRef.current || !connectionReady) {
      setMicError('口语服务尚未连接，请稍后重试。');
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      // Barge-in: the microphone is open and the learner is about to speak, so stop the tutor's
      // playback and tell the channel to abandon a reply it is still producing (ADR-0011).
      stopSpeakingAudio();
      suppressPlaybackRef.current = true;
      connectionRef.current?.bargeIn();
      setThinking(false);
      const recorder = new MediaRecorder(stream);
      recorderRef.current = recorder;
      chunksRef.current = [];
      setMicError(null);

      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunksRef.current.push(event.data);
        }
      };
      recorder.onstop = () => {
        const mimeType = recorder.mimeType || 'audio/webm';
        const blob = new Blob(chunksRef.current, { type: mimeType });
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        setIsRecording(false);
        if (blob.size === 0) {
          return;
        }
        void blobToBase64(blob).then((base64) => {
          setThinking(true);
          setFallbackReason(null);
          setFallbackNotice(null);
          setError(null);
          // A new utterance is on its way, so its reply may play normally.
          suppressPlaybackRef.current = false;
          connectionRef.current?.sendAudio(base64, mimeType, sessionIdRef.current);
        }).catch(() => {
          setMicError('无法读取录音数据，请使用文字输入。');
        });
      };

      recorder.start();
      setIsRecording(true);
    } catch {
      setMicError('无法访问麦克风，请检查权限或改用文字输入。');
      setIsRecording(false);
    }
  }

  function handleSend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = draft.trim();
    if (!message || thinking) {
      return;
    }
    if (!connectionRef.current || !connectionReady) {
      setError('口语服务尚未连接，请稍后重试。');
      return;
    }

    stopSpeakingAudio();
    suppressPlaybackRef.current = false;
    connectionRef.current.sendText(message, sessionIdRef.current);
    setDraft('');
    setThinking(true);
    setFallbackReason(null);
    setFallbackNotice(null);
    setError(null);
  }

  function startNewPractice() {
    stopSpeakingAudio();
    suppressPlaybackRef.current = false;
    rememberSession(null);
    setTurns([]);
    setFallbackReason(null);
    setFallbackNotice(null);
    setDraft('');
    setThinking(false);
    setError(null);
    setMicError(null);
  }

  return (
    <section className="panel speaking-panel" aria-labelledby="speaking-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Speaking Practice</p>
          <h2 id="speaking-title">口语练习</h2>
        </div>
        <span
          className={`mode-pill ${mockMode ? 'mode-mock' : 'mode-real'}`}
          title={mockMode ? '口语回复由确定性示例适配器生成' : '口语回复由真实模型生成'}
        >
          <span className="mode-dot" aria-hidden="true" />
          {mockMode ? 'Mock 模式' : '真实模型'}
        </span>
      </div>

      <p className="speaking-anchor">
        说一句英语，系统会转写、回答并朗读。语音失败或超时时自动退回文字输入，本次 Speaking Session 会保存下来供回放。
      </p>

      {latencyStats && latencyStats.turnCount > 0 && (
        <p
          className={`speaking-latency ${latencyStats.withinBudget ? '' : 'is-over-budget'}`}
          role="status"
        >
          说完到开始播放（服务端）p95 {formatSeconds(latencyStats.p95Ms)}（目标{' '}
          {formatSeconds(latencyStats.budgetMs)}）{latencyStats.withinBudget ? '' : '，超出预算'}
        </p>
      )}

      {sessions.length > 0 && (
        <div className="speaking-history" aria-label="历史 Speaking Session">
          <span className="muted">历史 Speaking Session</span>
          {sessions.map((item, index) => (
            <button
              key={item.id}
              type="button"
              className={`text-button ${item.id === sessionId ? 'is-current' : ''}`}
              onClick={() => void loadSession(item.id)}
            >
              Speaking Session {index + 1}
            </button>
          ))}
        </div>
      )}

      {loadStatus === 'loading' && (
        <p className="muted" role="status">
          正在读取已保存的口语练习…
        </p>
      )}

      {loadStatus === 'error' && (
        <div className="panel-error" role="alert">
          <p>{loadError ?? '加载 Speaking Session 失败，请稍后重试。'}</p>
        </div>
      )}

      {loadStatus === 'ready' && turns.length === 0 && !thinking && (
        <div className="conversation-empty">
          <p>还没有 Speaking Session。</p>
        </div>
      )}

      {(turns.length > 0 || thinking) && (
        <ol className="speaking-turns" aria-label="Speaking Session 回合">
          {turns.map((turn) => (
            <li key={turn.key} className="speaking-turn">
              <div className="speaking-transcript-row">
                <span className="speaking-input-kind">
                  {turn.inputMode === 'VOICE' ? '语音' : '文字'}
                </span>
                <p className="speaking-transcript">
                  {turn.transcript || '（没有识别到语音）'}
                </p>
              </div>
              {turn.reply && <div className="speaking-reply">{turn.reply}</div>}
              {turn.fallbackReason && (
                <p className="speaking-fallback">{fallbackLabels[turn.fallbackReason]}</p>
              )}
            </li>
          ))}
          {thinking && (
            <li
              className={`speaking-turn speaking-thinking ${thinkingOverBudget ? 'is-over-budget' : ''}`}
              aria-live="polite"
            >
              <span className="spinner" aria-hidden="true" />
              <span>{thinkingOverBudget ? '思考中…' : '正在等待回复…'}</span>
            </li>
          )}
        </ol>
      )}

      {(fallbackNotice || fallbackReason) && (
        <div className="speaking-fallback-banner" role="status">
          {fallbackNotice ?? (fallbackReason ? fallbackLabels[fallbackReason] : null)}
        </div>
      )}

      {micError && (
        <div className="panel-error" role="alert">
          <p>{micError}</p>
        </div>
      )}

      {error && (
        <div className="panel-error" role="alert">
          <p>{error}</p>
        </div>
      )}

      <div className="speaking-controls">
        <button
          type="button"
          className="text-button"
          onClick={startNewPractice}
          disabled={thinking || isRecording}
        >
          新的练习
        </button>
        <button
          type="button"
          className={isRecording ? 'retry-button is-recording' : 'retry-button'}
          onClick={() => void toggleRecording()}
          disabled={!connectionReady || !canRecord}
        >
          {isRecording ? '停止录音并发送' : '开始录音'}
        </button>
        <span className="muted">
          {isRecording
            ? '正在录音…'
            : canRecord
              ? '也可以直接在下面输入英文。'
              : '当前环境不支持录音，请使用文字输入。'}
        </span>
      </div>

      <form className="conversation-form" onSubmit={handleSend}>
        <label className="sr-only" htmlFor="speaking-input">
          说或输入一句英语
        </label>
        <textarea
          id="speaking-input"
          className="conversation-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="也可以在这里输入英文，例如：I went to the park yesterday."
          rows={3}
          disabled={thinking}
        />
        <div className="conversation-actions">
          <span className="privacy-note">语音与文字回合会保存在本次 Speaking Session。</span>
          <button
            type="submit"
            className="retry-button"
            disabled={thinking || !connectionReady || draft.trim().length === 0}
          >
            {thinking ? '正在回复…' : '发送'}
          </button>
        </div>
      </form>
    </section>
  );
}
