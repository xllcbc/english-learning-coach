import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LearningReviewPanel } from '../components/LearningReviewPanel';
import type { LearningReview } from '../types';

const api = vi.hoisted(() => ({
  getLearningReview: vi.fn(),
}));

vi.mock('../api', () => ({
  getLearningReview: api.getLearningReview,
  toErrorMessage: (error: unknown) =>
    error instanceof Error ? error.message : '无法连接后端服务，请确认服务已启动。',
}));

function review(overrides: Partial<LearningReview> = {}): LearningReview {
  return {
    status: 'SUFFICIENT_DATA',
    message: '基于最近 4 次 Practice Attempt，发现 2 类反复出现的学习问题。',
    attemptCount: 4,
    recentAttemptLimit: 10,
    estimatedLevelChange: {
      fromLevel: 'B1',
      toLevel: 'B2',
      delta: 1,
      direction: 'IMPROVING',
      points: [
        {
          practiceAttemptId: 'practice-1',
          level: 'B1',
          createdAt: '2026-11-01T09:00:00Z',
        },
        {
          practiceAttemptId: 'practice-4',
          level: 'B2',
          createdAt: '2026-11-04T09:00:00Z',
        },
      ],
    },
    studyPlan: [
      {
        priority: 1,
        category: 'GRAMMAR',
        categoryLabel: '语法',
        severity: 'MINOR',
        occurrenceCount: 4,
        attemptCount: 4,
        priorityScore: 48,
        action: '优先复习「语法」类学习问题，并用最近一次的原句做最小改写练习。',
        evidence: [
          {
            learningIssueId: 'issue-1',
            practiceAttemptId: 'practice-1',
            practiceCreatedAt: '2026-11-01T09:00:00Z',
            originalSentence: 'She go to school.',
            suggestion: 'She goes to school.',
            explanation: '第三人称单数需要加 -s。',
          },
        ],
        groundingMaterials: [
          {
            id: 'material-1',
            category: 'GRAMMAR',
            content: '主谓一致：主语是第三人称单数时，现在时的谓语动词要加 -s。',
            source: 'WriteLab 自建依据材料',
            license: 'CC BY 4.0',
          },
        ],
      },
      {
        priority: 2,
        category: 'COHERENCE',
        categoryLabel: '连贯性',
        severity: 'MAJOR',
        occurrenceCount: 3,
        attemptCount: 3,
        priorityScore: 41,
        action: '优先复习「连贯性」类学习问题。',
        evidence: [
          {
            learningIssueId: 'issue-2',
            practiceAttemptId: 'practice-2',
            practiceCreatedAt: '2026-11-02T09:00:00Z',
            originalSentence: 'The paragraph jumps.',
            suggestion: 'In addition, the paragraph connects.',
            explanation: '使用连接词让论证更连贯。',
          },
        ],
        groundingMaterials: [],
      },
    ],
    ...overrides,
  };
}

describe('LearningReviewPanel', () => {
  beforeEach(() => {
    api.getLearningReview.mockReset();
  });

  it('shows the prioritized Study Plan with traceable Learning Issues and the Estimated Level change', async () => {
    api.getLearningReview.mockResolvedValue(review());

    render(<LearningReviewPanel />);

    expect(await screen.findByText(/发现 2 类反复出现/)).toBeInTheDocument();
    expect(screen.getAllByText('B1').length).toBeGreaterThan(0);
    expect(screen.getAllByText('B2').length).toBeGreaterThan(0);
    expect(screen.getByText('提升')).toBeInTheDocument();

    const items = screen.getAllByRole('listitem', { name: /优先级/ });
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('优先 1');
    expect(items[0]).toHaveTextContent('语法');
    expect(items[0]).toHaveTextContent('轻微');
    expect(items[0]).toHaveTextContent('最近 4 次练习中出现 4 次');
    expect(items[0]).toHaveTextContent('She go to school.');
    expect(items[0]).toHaveTextContent('Learning Issue · issue-1');
    expect(items[0]).toHaveTextContent('主谓一致');
    expect(items[1]).toHaveTextContent('优先 2');
    expect(items[1]).toHaveTextContent('连贯性');
  });

  it('states that there is not enough data instead of showing a fabricated plan', async () => {
    api.getLearningReview.mockResolvedValue(review({
      status: 'INSUFFICIENT_DATA',
      message: '最近只有 1 次 Practice Attempt，至少需要 3 次才能诊断反复出现的学习问题。',
      attemptCount: 1,
      estimatedLevelChange: null,
      studyPlan: [],
    }));

    render(<LearningReviewPanel />);

    expect(await screen.findByText(/至少需要 3 次/)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Study Plan' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Estimated Level 变化')).not.toBeInTheDocument();
    expect(screen.getByText(/还没有足够的反复问题/)).toBeInTheDocument();
  });

  it('shows a load error with a retry action', async () => {
    api.getLearningReview.mockRejectedValueOnce(new Error('复盘服务不可用'));
    api.getLearningReview.mockResolvedValueOnce(review());

    render(<LearningReviewPanel />);

    expect(await screen.findByText('复盘服务不可用')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText(/发现 2 类反复出现/)).toBeInTheDocument();
  });

  it('refreshes the review on demand', async () => {
    api.getLearningReview.mockResolvedValue(review());

    render(<LearningReviewPanel />);
    await screen.findByText(/发现 2 类反复出现/);

    await userEvent.click(screen.getByRole('button', { name: '重新复盘' }));

    expect(api.getLearningReview).toHaveBeenCalledTimes(2);
  });
});
