import type {
  ApiError,
  DictionaryEntry,
  DocumentCitation,
  GroundingCitation,
  PracticeResponse,
  PracticeSummary,
  SavedVocabularyEntry,
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

export function createPractice(text: string): Promise<PracticeResponse> {
  return request<PracticeResponse>('/api/practices', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ text }),
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

  let completed = false;
  let errored = false;

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
      completed = true;
      handlers.onComplete(
        payload.explanation,
        payload.groundingMaterials ?? [],
        payload.documentCitations ?? [],
      );
    } else if (name === 'error') {
      const payload = JSON.parse(data) as ApiError;
      errored = true;
      handlers.onError(payload.message || '教师讲解生成失败，请重试。');
    }
  });

  if (!completed && !errored) {
    handlers.onError('教师讲解流意外中断，请重试。');
  }
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

  let completed = false;
  let errored = false;

  await readSse(response, (name, data) => {
    if (name === 'start') {
      handlers.onStart?.(JSON.parse(data) as ConversationStart);
    } else if (name === 'delta') {
      const payload = JSON.parse(data) as { text: string };
      handlers.onDelta(payload.text);
    } else if (name === 'complete') {
      const payload = JSON.parse(data) as { turn: TeacherConversationTurn };
      completed = true;
      handlers.onComplete(payload.turn);
    } else if (name === 'error') {
      const payload = JSON.parse(data) as ApiError;
      errored = true;
      handlers.onError(payload.message || '教师回答生成失败，请重试。');
    }
  });

  if (!completed && !errored) {
    handlers.onError('教师对话流意外中断，请重试。');
  }
}

/**
 * Shared Server-Sent Events reader. Both the Teacher Explanation and Teacher Conversation
 * channels use the same `event:` + `data:` framing, so one parser keeps their behavior aligned.
 */
async function readSse(
  response: Response,
  onEvent: (name: string, data: string) => void,
): Promise<void> {
  if (!response.body) {
    throw new ApiRequestError('STREAM_UNAVAILABLE', '无法读取流式回答，请重试。');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];

  const dispatch = () => {
    if (dataLines.length === 0) {
      eventName = 'message';
      return;
    }

    const data = dataLines.join('\n');
    const name = eventName;
    dataLines = [];
    eventName = 'message';
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
}

