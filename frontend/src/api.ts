import type {
  ApiError,
  DictionaryEntry,
  PracticeResponse,
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, init);
  } catch {
    throw new ApiRequestError('NETWORK_ERROR', '无法连接后端服务，请确认服务已启动。');
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

export function createPractice(text: string): Promise<PracticeResponse> {
  return request<PracticeResponse>('/api/practices', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ text }),
  });
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
