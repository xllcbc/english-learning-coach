import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { TeacherConversationPanel } from '../components/TeacherConversationPanel';
import type { TeacherConversation, TeacherConversationTurn } from '../types';

const api = vi.hoisted(() => ({
  getConversation: vi.fn(),
  streamConversationTurn: vi.fn(),
}));

vi.mock('../api', () => ({
  getConversation: api.getConversation,
  streamConversationTurn: api.streamConversationTurn,
  toErrorMessage: (error: unknown) =>
    error instanceof Error ? error.message : '无法连接后端服务，请确认服务已启动。',
}));

function conversation(
  turns: TeacherConversationTurn[],
  mockMode = true,
): TeacherConversation {
  return {
    practiceAttemptId: 'practice-1',
    conversationId: turns.length === 0 ? null : 'conversation-1',
    modelMode: mockMode ? 'MOCK' : 'REAL',
    mockMode,
    turns,
  };
}

function turn(overrides: Partial<TeacherConversationTurn> = {}): TeacherConversationTurn {
  return {
    id: 'turn-1',
    position: 1,
    learnerMessage: '为什么这里用 moreover？',
    answer: '用连接词会更连贯。',
    stopReason: 'COMPLETED',
    createdAt: '2026-11-25T08:00:00Z',
    steps: [],
    citations: [],
    toolInvocations: [],
    ...overrides,
  };
}

describe('TeacherConversationPanel', () => {
  beforeEach(() => {
    api.getConversation.mockReset();
    api.streamConversationTurn.mockReset();
  });

  it('replays the turns already stored for this Practice Attempt without asking the model', async () => {
    api.getConversation.mockResolvedValue(conversation([
      turn({
        learnerMessage: '已经保存的问题',
        answer: '已经保存的回答 [依据1]。',
        citations: [
          {
            reference: 1,
            kind: 'GROUNDING_MATERIAL',
            title: '连贯性',
            content: '用连接词显式标明逻辑关系。',
            source: 'WriteLab 自建依据材料',
            license: 'CC BY 4.0',
          },
        ],
      }),
    ]));

    render(<TeacherConversationPanel practiceId="practice-1" defaultMockMode />);

    expect(await screen.findByText('已经保存的问题')).toBeInTheDocument();
    expect(screen.getByText('已经保存的回答 [依据1]。')).toBeInTheDocument();
    expect(screen.getByText('本次回答的依据')).toBeInTheDocument();
    expect(screen.getByText('依据材料')).toBeInTheDocument();
    expect(screen.getByText('Mock 模式')).toBeInTheDocument();
    expect(api.streamConversationTurn).not.toHaveBeenCalled();
  });

  it('streams a new question and appends the saved turn with its citations', async () => {
    api.getConversation.mockResolvedValue(conversation([]));
    api.streamConversationTurn.mockImplementation(
      async (
        _practiceId: string,
        _message: string,
        handlers: {
          onStart?: (start: {
            practiceId: string;
            conversationId: string;
            turnPosition: number;
            mockMode: boolean;
          }) => void;
          onDelta: (text: string) => void;
          onComplete: (turn: TeacherConversationTurn) => void;
        },
      ) => {
        handlers.onStart?.({
          practiceId: 'practice-1',
          conversationId: 'conversation-1',
          turnPosition: 1,
          mockMode: false,
        });
        handlers.onDelta('用连接词');
        handlers.onDelta('会更连贯。');
        handlers.onComplete(turn({
          answer: '用连接词会更连贯。',
          citations: [
            {
              reference: 1,
              kind: 'DICTIONARY_ENTRY',
              title: 'Dictionary Entry · moreover',
              content: '/mɔːrˈoʊvər/ 副词 此外；而且。',
              source: '内置词典',
              license: null,
            },
          ],
        }));
      },
    );

    render(<TeacherConversationPanel practiceId="practice-1" defaultMockMode={false} />);

    await screen.findByText(/还没有对话/);
    await userEvent.type(
      screen.getByLabelText('就这份 Practice Attempt 追问'),
      '为什么用 moreover？',
    );
    await userEvent.click(screen.getByRole('button', { name: '提问' }));

    expect(await screen.findByText('用连接词会更连贯。')).toBeInTheDocument();
    expect(screen.getByText('本次回答的依据')).toBeInTheDocument();
    expect(screen.getByText('词典条目')).toBeInTheDocument();
    expect(screen.getByText('Dictionary Entry · moreover')).toBeInTheDocument();
    expect(screen.getByText('真实模型')).toBeInTheDocument();
    expect(api.streamConversationTurn).toHaveBeenCalledWith(
      'practice-1',
      '为什么用 moreover？',
      expect.anything(),
      expect.anything(),
    );
  });

  it('shows the streamed error without leaving a pending turn behind', async () => {
    api.getConversation.mockResolvedValue(conversation([]));
    api.streamConversationTurn.mockImplementation(
      async (
        _practiceId: string,
        _message: string,
        handlers: { onError: (message: string) => void },
      ) => {
        handlers.onError('教师回答生成失败，请重试。');
      },
    );

    render(<TeacherConversationPanel practiceId="practice-1" defaultMockMode />);

    await screen.findByText(/还没有对话/);
    await userEvent.type(
      screen.getByLabelText('就这份 Practice Attempt 追问'),
      '这个问题会失败吗？',
    );
    await userEvent.click(screen.getByRole('button', { name: '提问' }));

    expect(await screen.findByText('教师回答生成失败，请重试。')).toBeInTheDocument();
    expect(screen.queryByText('正在思考…')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('教师对话回合')).not.toBeInTheDocument();
  });

  it('shows a load error with a retry action', async () => {
    api.getConversation.mockRejectedValueOnce(new Error('服务不可用'));
    api.getConversation.mockResolvedValueOnce(conversation([]));

    render(<TeacherConversationPanel practiceId="practice-1" defaultMockMode />);

    expect(await screen.findByText('服务不可用')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '重新加载' }));
    expect(await screen.findByText(/还没有对话/)).toBeInTheDocument();
  });
});
