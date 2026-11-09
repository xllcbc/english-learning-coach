import type { ApiError, PracticeResponse } from './types';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');

export class ApiRequestError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'ApiRequestError';
    this.code = code;
  }
}

export async function createPractice(text: string): Promise<PracticeResponse> {
  const response = await fetch(`${API_BASE_URL}/api/practices`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ text }),
  });

  const payload: unknown = await response.json().catch(() => null);

  if (!response.ok) {
    const error = payload as ApiError | null;
    throw new ApiRequestError(
      error?.code ?? 'HTTP_ERROR',
      error?.message ?? '请求失败，请稍后重试。',
    );
  }

  return payload as PracticeResponse;
}
