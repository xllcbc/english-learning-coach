import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { TargetedPracticePanel } from '../components/TargetedPracticePanel';
import type { TargetedPractice } from '../types';

function task(overrides: Partial<TargetedPractice> = {}): TargetedPractice {
  return {
    id: 'targeted-1',
    createdAt: '2026-11-10T09:00:00Z',
    priority: 2,
    category: 'COHERENCE',
    categoryLabel: '连贯性',
    severity: 'MAJOR',
    focus: '本次聚焦「连贯性」类学习问题（严重）：段落之间需要明确的连接词。',
    prompt: 'Write a 200-500 word essay in English: Describe a time when you changed your mind.\n\nFocus: Use explicit linking devices between sentences and paragraphs.',
    evidence: [
      {
        learningIssueId: 'issue-1',
        practiceAttemptId: 'practice-1',
        practiceCreatedAt: '2026-11-01T09:00:00Z',
        originalSentence: 'The paragraph jumps.',
        suggestion: 'In addition, the paragraph connects.',
        explanation: '段落之间需要明确的连接词。',
      },
    ],
    groundingMaterials: [],
    occurrenceCount: 2,
    attemptCount: 2,
    beforePracticeAttemptId: 'practice-2',
    beforeCreatedAt: '2026-11-02T09:00:00Z',
    beforeCount: 1,
    status: 'PENDING',
    practiceAttemptId: null,
    completedAt: null,
    comparison: null,
    ...overrides,
  };
}

describe('TargetedPracticePanel', () => {
  it('shows the single weak item and prompt while the task is pending', () => {
    render(<TargetedPracticePanel practice={task()} />);

    expect(screen.getByText('针对性练习')).toBeInTheDocument();
    expect(screen.getByText(/本次聚焦「连贯性」/)).toBeInTheDocument();
    expect(screen.getByText(/Write a 200-500 word essay/)).toBeInTheDocument();
    expect(screen.getByText(/最近 2 次练习中出现 2 次/)).toBeInTheDocument();
    expect(screen.queryByLabelText('针对性问题练前练后对比')).not.toBeInTheDocument();
  });

  it('shows the targeted issue before and after once the Practice Attempt is submitted', () => {
    render(
      <TargetedPracticePanel
        practice={task({
          status: 'SUBMITTED',
          practiceAttemptId: 'practice-3',
          completedAt: '2026-11-03T09:00:00Z',
          comparison: {
            beforePracticeAttemptId: 'practice-2',
            beforeCreatedAt: '2026-11-02T09:00:00Z',
            beforeCount: 1,
            afterPracticeAttemptId: 'practice-3',
            afterCreatedAt: '2026-11-03T09:00:00Z',
            afterCount: 0,
            change: 1,
            trend: 'IMPROVED',
          },
        })}
      />,
    );

    const comparison = screen.getByLabelText('针对性问题练前练后对比');
    expect(within(comparison).getByText('练前')).toBeInTheDocument();
    expect(within(comparison).getByText('练后')).toBeInTheDocument();
    expect(within(comparison).getByText('1 次')).toBeInTheDocument();
    expect(within(comparison).getByText('0 次')).toBeInTheDocument();
    expect(within(comparison).getByText('改善')).toBeInTheDocument();
    expect(within(comparison).getByText(/减少 1 次/)).toBeInTheDocument();
    expect(screen.queryByText(/Write a 200-500 word essay/)).not.toBeInTheDocument();
  });
});
