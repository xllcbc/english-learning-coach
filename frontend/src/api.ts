import type {
  ApiError,
  DictionaryEntry,
  DocumentCitation,
  GroundingCitation,
  IssueCategory,
  IssueSeverity,
  LearningReview,
  PracticeResponse,
  PracticeSummary,
  SavedVocabularyEntry,
  SpeakingAudioReply,
  SpeakingChannelError,
  SpeakingInputMode,
  SpeakingInterrupted,
  SpeakingLatencyStats,
  SpeakingServerMessage,
  SpeakingSession,
  SpeakingSessionSummary,
  SpeakingTextReply,
  TargetedPractice,
  TeacherConversation,
  TeacherConversationTurn,
  UserDocumentSummary,
  UserDocumentUploadResponse,
} from './types';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');

export class ApiRequestError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'ApiRequestError';
    this.code = code;
  }
}

export function toErrorMessage(error: unknown): string {
  return error instanceof ApiRequestError
    ? error.message
    : '无法连接后端服务，请确认服务已启动。';
}

async function request<T>(path: string, init?: RequestInit, timeoutMs?: number): Promise<T> {
  const controller = new AbortController();
  const timer = timeoutMs === undefined ? undefined : setTimeout(() => controller.abort(), timeoutMs);

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, signal: controller.signal });
  } catch {
    if (controller.signal.aborted) {
      throw new ApiRequestError('REQUEST_TIMEOUT', '等待模型响应超时，请稍后重试。');
    }
    throw new ApiRequestError('NETWORK_ERROR', '无法连接后端服务，请确认服务已启动。');
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  }

  const payload: unknown = response.status === 204
    ? null
    : await response.json().catch(() => null);

  if (!response.ok) {
    const error = payload as ApiError | null;
    throw new ApiRequestError(
      error?.code ?? 'HTTP_ERROR',
      error?.message ?? '请求失败，请稍后重试。',
    );
  }

  return payload as T;
}

// Backend worst case is 2 x 120s calls plus backoff; abort only after the backend could have answered.
const PRACTICE_TIMEOUT_MS = 250_000;

export function createPractice(
  text: string,
  targetedPracticeId?: string,
): Promise<PracticeResponse> {
  return request<PracticeResponse>('/api/practices', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      text,
      ...(targetedPracticeId ? { targetedPracticeId } : {}),
    }),
  }, PRACTICE_TIMEOUT_MS);
}

export function lookupWord(word: string): Promise<DictionaryEntry> {
  return request<DictionaryEntry>(`/api/dictionary/${encodeURIComponent(word)}`);
}

export function saveVocabulary(
  practiceAttemptId: string,
  word: string,
): Promise<SavedVocabularyEntry> {
  return request<SavedVocabularyEntry>('/api/saved-vocabulary', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ practiceAttemptId, word }),
  });
}

export function listSavedVocabulary(): Promise<SavedVocabularyEntry[]> {
  return request<SavedVocabularyEntry[]>('/api/saved-vocabulary');
}

export function deleteSavedVocabulary(id: string): Promise<void> {
  return request<void>(`/api/saved-vocabulary/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export function listPractices(): Promise<PracticeSummary[]> {
  return request<PracticeSummary[]>('/api/practices');
}

export function getPractice(id: string): Promise<PracticeResponse> {
  return request<PracticeResponse>(`/api/practices/${encodeURIComponent(id)}`);
}

export function getLearningReview(): Promise<LearningReview> {
  return request<LearningReview>('/api/learning-review');
}

export function createTargetedPractice(
  category: IssueCategory,
  severity: IssueSeverity,
): Promise<TargetedPractice> {
  return request<TargetedPractice>('/api/targeted-practices', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ category, severity }),
  });
}

export function getTargetedPractice(id: string): Promise<TargetedPractice> {
  return request<TargetedPractice>(`/api/targeted-practices/${encodeURIComponent(id)}`);
}

export function getTargetedPracticeForPractice(
  practiceId: string,
): Promise<TargetedPractice | null> {
  return request<TargetedPractice | null>(
    `/api/targeted-practices/by-practice/${encodeURIComponent(practiceId)}`,
  );
}

export function listUserDocuments(): Promise<UserDocumentSummary[]> {
  return request<UserDocumentSummary[]>('/api/documents');
}

export function getUserDocument(id: string): Promise<UserDocumentSummary> {
  return request<UserDocumentSummary>(`/api/documents/${encodeURIComponent(id)}`);
}

export function uploadUserDocument(file: File): Promise<UserDocumentUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  return request<UserDocumentUploadResponse>('/api/documents', {
    method: 'POST',
    body: form,
  });
}

export function retryUserDocument(id: string): Promise<UserDocumentSummary> {
  return request<UserDocumentSummary>(`/api/documents/${encodeURIComponent(id)}/retry`, {
    method: 'POST',
  });
}

export function deleteUserDocument(id: string): Promise<void> {
  return request<void>(`/api/documents/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export function setUserDocumentActive(id: string, active: boolean): Promise<UserDocumentSummary> {
  return request<UserDocumentSummary>(`/api/documents/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ active }),
  });
}

export interface ModelModeInfo {
  modelMode: 'MOCK' | 'REAL';
  mockMode: boolean;
}

export function getModelMode(): Promise<ModelModeInfo> {
  return request<ModelModeInfo>('/api/model-mode');
}

export interface ExplanationStreamHandlers {
  onStart?: (replay: boolean) => void;
  onDelta: (text: string) => void;
  onComplete: (
    explanation: string,
    groundingMaterials: GroundingCitation[],
    documentCitations: DocumentCitation[],
  ) => void;
  onError: (message: string) => void;
}

export async function streamExplanation(
  practiceId: string,
  handlers: ExplanationStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/practices/${encodeURIComponent(practiceId)}/explanation`,
    {
      headers: {
        Accept: 'text/event-stream, application/json',
      },
      signal,
    },
  );

  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as ApiError | null;
    throw new ApiRequestError(
      payload?.code ?? 'HTTP_ERROR',
      payload?.message ?? '教师讲解请求失败，请稍后重试。',
    );
  }

  await readSse(response, (name, data) => {
    if (name === 'start') {
      const payload = JSON.parse(data) as { replay?: boolean };
      handlers.onStart?.(payload.replay ?? false);
    } else if (name === 'delta') {
      const payload = JSON.parse(data) as { text: string };
      handlers.onDelta(payload.text);
    } else if (name === 'complete') {
      const payload = JSON.parse(data) as {
        explanation: string;
        groundingMaterials?: GroundingCitation[];
        documentCitations?: DocumentCitation[];
      };
      handlers.onComplete(
        payload.explanation,
        payload.groundingMaterials ?? [],
        payload.documentCitations ?? [],
      );
    } else if (name === 'error') {
      const payload = JSON.parse(data) as ApiError;
      handlers.onError(payload.message || '教师讲解生成失败，请重试。');
    }
  }, () => handlers.onError('教师讲解流意外中断，请重试。'));
}

export function getConversation(practiceId: string): Promise<TeacherConversation> {
  return request<TeacherConversation>(
    `/api/practices/${encodeURIComponent(practiceId)}/conversation`,
  );
}

export interface ConversationStart {
  practiceId: string;
  conversationId: string;
  turnPosition: number;
  mockMode: boolean;
}

export interface ConversationStreamHandlers {
  onStart?: (start: ConversationStart) => void;
  onDelta: (text: string) => void;
  onComplete: (turn: TeacherConversationTurn) => void;
  onError: (message: string) => void;
}

export async function streamConversationTurn(
  practiceId: string,
  message: string,
  handlers: ConversationStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/practices/${encodeURIComponent(practiceId)}/conversation/turns`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream, application/json',
      },
      body: JSON.stringify({ message }),
      signal,
    },
  );

  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as ApiError | null;
    throw new ApiRequestError(
      payload?.code ?? 'HTTP_ERROR',
      payload?.message ?? '教师对话请求失败，请稍后重试。',
    );
  }

  await readSse(response, (name, data) => {
    if (name === 'start') {
      handlers.onStart?.(JSON.parse(data) as ConversationStart);
    } else if (name === 'delta') {
      const payload = JSON.parse(data) as { text: string };
      handlers.onDelta(payload.text);
    } else if (name === 'complete') {
      const payload = JSON.parse(data) as { turn: TeacherConversationTurn };
      handlers.onComplete(payload.turn);
    } else if (name === 'error') {
      const payload = JSON.parse(data) as ApiError;
      handlers.onError(payload.message || '教师回答生成失败，请重试。');
    }
  }, () => handlers.onError('教师对话流意外中断，请重试。'));
}

/**
 * Shared Server-Sent Events reader. Both the Teacher Explanation and Teacher Conversation
 * channels use the same `event:` + `data:` framing, so one parser keeps their behavior aligned.
 */
async function readSse(
  response: Response,
  onEvent: (name: string, data: string) => void,
  onInterrupted?: () => void,
): Promise<void> {
  if (!response.body) {
    throw new ApiRequestError('STREAM_UNAVAILABLE', '无法读取流式回答，请重试。');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];
  let terminal = false;

  const dispatch = () => {
    if (dataLines.length === 0) {
      eventName = 'message';
      return;
    }

    const data = dataLines.join('\n');
    const name = eventName;
    dataLines = [];
    eventName = 'message';
    if (name === 'complete' || name === 'error') {
      terminal = true;
    }
    onEvent(name, data);
  };

  const processLine = (line: string) => {
    if (line === '') {
      dispatch();
    } else if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let newlineIndex = buffer.indexOf('\n');
    while (newlineIndex >= 0) {
      const line = buffer.slice(0, newlineIndex).replace(/\r$/, '');
      buffer = buffer.slice(newlineIndex + 1);
      processLine(line);
      newlineIndex = buffer.indexOf('\n');
    }
  }

  buffer += decoder.decode();
  for (const line of buffer.split('\n')) {
    processLine(line.replace(/\r$/, ''));
  }

  if (!terminal) {
    onInterrupted?.();
  }
}


export function listSpeakingSessions(): Promise<SpeakingSessionSummary[]> {
  return request<SpeakingSessionSummary[]>('/api/speaking/sessions');
}

export function getSpeakingSession(id: string): Promise<SpeakingSession> {
  return request<SpeakingSession>(`/api/speaking/sessions/${encodeURIComponent(id)}`);
}

/** Observed "finished speaking to playback" latency over every persisted speaking turn. */
export function getSpeakingLatency(): Promise<SpeakingLatencyStats> {
  return request<SpeakingLatencyStats>('/api/speaking/latency');
}

export interface SpeakingChannelHandlers {
  onOpen?: () => void;
  onAudio?: (reply: SpeakingAudioReply) => void;
  onText?: (reply: SpeakingTextReply) => void;
  onInterrupted?: (interrupted: SpeakingInterrupted) => void;
  onError?: (error: SpeakingChannelError) => void;
  onClose?: () => void;
}

export interface SpeakingChannelConnection {
  sendAudio(base64Audio: string, mimeType: string, sessionId: string | null): void;
  sendText(text: string, sessionId: string | null): void;
  /** Announces that the learner started speaking so the channel can cancel a reply in flight. */
  bargeIn(): void;
  close(): void;
}

/**
 * The voice channel is the only WebSocket surface in the app. A typed message uses the same socket
 * as audio, so a failed transcription can continue on the same Speaking Session (ADR-0011).
 */
export function openSpeakingChannel(
  handlers: SpeakingChannelHandlers,
): SpeakingChannelConnection {
  const socketUrl = `${API_BASE_URL.replace(/^http/, 'ws')}/ws/speaking`;
  const socket = new WebSocket(socketUrl);

  socket.onopen = () => handlers.onOpen?.();
  socket.onclose = () => handlers.onClose?.();
  socket.onerror = () => handlers.onError?.({
    code: 'SPEAKING_CHANNEL_ERROR',
    message: '无法连接口语服务，请确认服务已启动。',
  });
  socket.onmessage = (event: MessageEvent<string>) => {
    let payload: SpeakingServerMessage | SpeakingChannelError & { type: string };
    try {
      payload = JSON.parse(event.data) as SpeakingServerMessage | SpeakingChannelError & { type: string };
    } catch {
      handlers.onError?.({ code: 'INVALID_MESSAGE', message: '口语服务返回了无法识别的消息。' });
      return;
    }

    if (payload.type === 'audio') {
      handlers.onAudio?.(payload as SpeakingAudioReply);
    } else if (payload.type === 'text') {
      handlers.onText?.(payload as SpeakingTextReply);
    } else if (payload.type === 'interrupted') {
      handlers.onInterrupted?.(payload as SpeakingInterrupted);
    } else if (payload.type === 'error') {
      const error = payload as SpeakingChannelError;
      handlers.onError?.({ code: error.code, message: error.message });
    }
  };

  return {
    sendAudio(base64Audio, mimeType, sessionId) {
      socket.send(JSON.stringify({
        type: 'audio',
        audio: base64Audio,
        mimeType,
        sessionId: sessionId ?? undefined,
      }));
    },
    sendText(text, sessionId) {
      socket.send(JSON.stringify({
        type: 'text',
        text,
        sessionId: sessionId ?? undefined,
      }));
    },
    bargeIn() {
      socket.send(JSON.stringify({ type: 'barge_in' }));
    },
    close() {
      socket.close();
    },
  };
}

let activeSpeakingAudio: HTMLAudioElement | null = null;

/**
 * Stops the reply currently being spoken. Starting a new turn calls this first so the learner can
 * barge in without two synthesized replies overlapping (ADR-0011).
 */
export function stopSpeakingAudio(): void {
  if (activeSpeakingAudio) {
    activeSpeakingAudio.pause();
    activeSpeakingAudio.currentTime = 0;
    activeSpeakingAudio = null;
  }
}

/**
 * Attempts automatic playback of a synthesized reply. Browsers can block autoplay; the panel still
 * renders the text reply, so a rejected play() is not an error the learner must handle.
 */
export function playSpeakingAudio(base64Audio: string, mimeType: string): void {
  stopSpeakingAudio();
  try {
    const audio = new Audio(`data:${mimeType};base64,${base64Audio}`);
    activeSpeakingAudio = audio;
    audio.onended = () => {
      if (activeSpeakingAudio === audio) {
        activeSpeakingAudio = null;
      }
    };
    void audio.play().catch(() => {
      if (activeSpeakingAudio === audio) {
        activeSpeakingAudio = null;
      }
    });
  } catch {
    // Audio is an enhancement; the text reply remains visible.
  }
}

export type { SpeakingInputMode };
