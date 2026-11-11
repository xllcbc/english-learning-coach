import type {
  ApiError,
  DictionaryEntry,
  GroundingCitation,
  PracticeResponse,
  PracticeSummary,
  SavedVocabularyEntry,
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
  onComplete: (explanation: string, groundingMaterials: GroundingCitation[]) => void;
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

  if (!response.body) {
    throw new ApiRequestError('STREAM_UNAVAILABLE', '无法读取教师讲解，请重试。');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];
  let completed = false;
  let errored = false;

  const dispatch = () => {
    if (dataLines.length === 0) {
      eventName = 'message';
      return;
    }

    const data = dataLines.join('\n');
    const name = eventName;
    dataLines = [];
    eventName = 'message';

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
      };
      completed = true;
      handlers.onComplete(payload.explanation, payload.groundingMaterials ?? []);
    } else if (name === 'error') {
      const payload = JSON.parse(data) as ApiError;
      errored = true;
      handlers.onError(payload.message || '教师讲解生成失败，请重试。');
    }
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

  if (!completed && !errored) {
    handlers.onError('教师讲解流意外中断，请重试。');
  }
}
