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

export interface ExplanationStreamHandlers {
  onStart?: (replay: boolean) => void;
  onDelta: (text: string) => void;
  onComplete: (explanation: string) => void;
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
      const payload = JSON.parse(data) as { explanation: string };
      completed = true;
      handlers.onComplete(payload.explanation);
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
