import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SpeakingPracticePanel } from '../components/SpeakingPracticePanel';
import type { SpeakingChannelHandlers } from '../api';
import type {
  SpeakingAudioReply,
  SpeakingSession,
  SpeakingSessionSummary,
  SpeakingTextReply,
} from '../types';

const api = vi.hoisted(() => ({
  listSpeakingSessions: vi.fn(),
  getSpeakingSession: vi.fn(),
  openSpeakingChannel: vi.fn(),
  playSpeakingAudio: vi.fn(),
  stopSpeakingAudio: vi.fn(),
}));

vi.mock('../api', () => ({
  listSpeakingSessions: api.listSpeakingSessions,
  getSpeakingSession: api.getSpeakingSession,
  openSpeakingChannel: api.openSpeakingChannel,
  playSpeakingAudio: api.playSpeakingAudio,
  stopSpeakingAudio: api.stopSpeakingAudio,
  toErrorMessage: (error: unknown) =>
    error instanceof Error ? error.message : '无法连接后端服务，请确认服务已启动。',
}));

function summary(overrides: Partial<SpeakingSessionSummary> = {}): SpeakingSessionSummary {
  return {
    id: 'session-1',
    modelMode: 'MOCK',
    mockMode: true,
    turnCount: 1,
    createdAt: '2026-11-25T08:00:00Z',
    ...overrides,
  };
}

function session(overrides: Partial<SpeakingSession> = {}): SpeakingSession {
  return {
    id: 'session-1',
    modelMode: 'MOCK',
    mockMode: true,
    createdAt: '2026-11-25T08:00:00Z',
    updatedAt: '2026-11-25T08:00:00Z',
    turns: [
      {
        id: 'turn-1',
        sessionId: 'session-1',
        position: 1,
        inputMode: 'VOICE',
        transcript: 'I went to the park yesterday.',
        reply: '你说得很清楚。试着补充一个原因。',
        fallbackReason: null,
        stopReason: 'COMPLETED',
        modelMode: 'MOCK',
        createdAt: '2026-11-25T08:00:00Z',
        steps: [],
        toolInvocations: [],
      },
    ],
    ...overrides,
  };
}

function captureChannel() {
  const connection = {
    sendAudio: vi.fn(),
    sendText: vi.fn(),
    bargeIn: vi.fn(),
    close: vi.fn(),
  };
  let handlers: SpeakingChannelHandlers = {};
  api.openSpeakingChannel.mockImplementation((candidate: SpeakingChannelHandlers) => {
    handlers = candidate;
    return connection;
  });
  return {
    getHandlers: () => handlers,
    connection,
  };
}

describe('SpeakingPracticePanel', () => {
  beforeEach(() => {
    api.listSpeakingSessions.mockReset();
    api.getSpeakingSession.mockReset();
    api.openSpeakingChannel.mockReset();
    api.playSpeakingAudio.mockReset();
    api.stopSpeakingAudio.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    delete (navigator as { mediaDevices?: unknown }).mediaDevices;
  });

  it('replays the latest Speaking Session and labels Mock mode', async () => {
    api.listSpeakingSessions.mockResolvedValue([summary()]);
    api.getSpeakingSession.mockResolvedValue(session());
    api.openSpeakingChannel.mockReturnValue({
      sendAudio: vi.fn(),
      sendText: vi.fn(),
      bargeIn: vi.fn(),
      close: vi.fn(),
    });

    render(<SpeakingPracticePanel defaultMockMode={false} />);

    expect(await screen.findByText('I went to the park yesterday.')).toBeInTheDocument();
    expect(screen.getByText('你说得很清楚。试着补充一个原因。')).toBeInTheDocument();
    expect(screen.getByText('Speaking Practice')).toBeInTheDocument();
    expect(screen.getByText('Mock 模式')).toBeInTheDocument();
    expect(api.getSpeakingSession).toHaveBeenCalledWith('session-1');
  });

  it('sends typed text, plays the audio reply, and keeps the session id', async () => {
    const captured = captureChannel();
    api.listSpeakingSessions.mockResolvedValue([]);

    render(<SpeakingPracticePanel defaultMockMode />);
    await screen.findByText('还没有 Speaking Session。');

    const handlers = captured.getHandlers();
    handlers.onOpen?.();

    await userEvent.type(
      screen.getByLabelText('说或输入一句英语'),
      'I like reading books.',
    );
    await userEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(captured.connection.sendText).toHaveBeenCalledWith('I like reading books.', null);
    expect(await screen.findByText('正在思考…')).toBeInTheDocument();

    const audioReply: SpeakingAudioReply = {
      type: 'audio',
      sessionId: 'session-1',
      audio: 'AAAA',
      mimeType: 'audio/wav',
      transcript: 'I like reading books.',
      reply: '很好，试着说明你喜欢哪一类书。',
      inputMode: 'TEXT',
      mockMode: true,
    };
    handlers.onAudio?.(audioReply);

    expect(await screen.findByText('很好，试着说明你喜欢哪一类书。')).toBeInTheDocument();
    expect(api.playSpeakingAudio).toHaveBeenCalledWith('AAAA', 'audio/wav');

    await userEvent.type(screen.getByLabelText('说或输入一句英语'), 'I enjoy history books.');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(captured.connection.sendText).toHaveBeenLastCalledWith(
      'I enjoy history books.',
      'session-1',
    );
  });

  it('barges in and stops waiting when the learner starts a new recording', async () => {
    const captured = captureChannel();
    api.listSpeakingSessions.mockResolvedValue([]);
    const getUserMedia = vi.fn().mockRejectedValue(new Error('no microphone'));
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia },
    });
    vi.stubGlobal('MediaRecorder', class MediaRecorder {});

    render(<SpeakingPracticePanel defaultMockMode />);
    await screen.findByText('还没有 Speaking Session。');
    captured.getHandlers().onOpen?.();

    await userEvent.type(screen.getByLabelText('说或输入一句英语'), 'A slow reply.');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('正在回复…')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '开始录音' }));

    expect(captured.connection.bargeIn).toHaveBeenCalledWith(null);
    expect(api.stopSpeakingAudio).toHaveBeenCalled();
    expect(screen.queryByText('正在回复…')).not.toBeInTheDocument();
    expect(await screen.findByText('无法访问麦克风，请检查权限或改用文字输入。')).toBeInTheDocument();
  });

  it('shows a transcription fallback and continues on the same session with text', async () => {
    const captured = captureChannel();
    api.listSpeakingSessions.mockResolvedValue([]);

    render(<SpeakingPracticePanel defaultMockMode />);
    await screen.findByText('还没有 Speaking Session。');

    const handlers = captured.getHandlers();
    handlers.onOpen?.();

    const fallback: SpeakingTextReply = {
      type: 'text',
      sessionId: 'session-2',
      text: '语音识别超时，请改用文字输入。',
      fallback: true,
      reason: 'timeout',
      transcript: '',
      inputMode: 'VOICE',
      mockMode: true,
    };
    handlers.onText?.(fallback);

    expect(await screen.findByText('语音识别超时，请改用文字输入。')).toBeInTheDocument();

    await userEvent.type(
      screen.getByLabelText('说或输入一句英语'),
      'I will keep practising by typing.',
    );
    await userEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(captured.connection.sendText).toHaveBeenCalledWith(
      'I will keep practising by typing.',
      'session-2',
    );
  });

  it('starts a new Speaking Session instead of appending to the replayed one', async () => {
    const captured = captureChannel();
    api.listSpeakingSessions.mockResolvedValue([summary()]);
    api.getSpeakingSession.mockResolvedValue(session());

    render(<SpeakingPracticePanel defaultMockMode />);
    await screen.findByText('I went to the park yesterday.');
    captured.getHandlers().onOpen?.();

    await userEvent.click(screen.getByRole('button', { name: '新的练习' }));
    expect(screen.queryByText('I went to the park yesterday.')).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('说或输入一句英语'), 'A fresh practice.');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(captured.connection.sendText).toHaveBeenCalledWith('A fresh practice.', null);
  });

  it('reopens a past session chosen by the learner', async () => {
    api.listSpeakingSessions.mockResolvedValue([
      summary({ id: 'session-1' }),
      summary({ id: 'session-2', turnCount: 2 }),
    ]);
    api.getSpeakingSession
      .mockResolvedValueOnce(session())
      .mockResolvedValueOnce(session({
        id: 'session-2',
        turns: [
          {
            ...session().turns[0],
            id: 'turn-2',
            sessionId: 'session-2',
            transcript: 'A different saved session.',
            reply: '这是另一段已保存的口语回合。',
          },
        ],
      }));
    api.openSpeakingChannel.mockReturnValue({
      sendAudio: vi.fn(),
      sendText: vi.fn(),
      bargeIn: vi.fn(),
      close: vi.fn(),
    });

    render(<SpeakingPracticePanel defaultMockMode />);
    await screen.findByText('I went to the park yesterday.');

    const historyButtons = await screen.findAllByRole('button', { name: /Speaking Session/ });
    await userEvent.click(historyButtons[1]);

    expect(await screen.findByText('A different saved session.')).toBeInTheDocument();
    expect(screen.getByText('这是另一段已保存的口语回合。')).toBeInTheDocument();
    expect(api.getSpeakingSession).toHaveBeenLastCalledWith('session-2');
  });
});
