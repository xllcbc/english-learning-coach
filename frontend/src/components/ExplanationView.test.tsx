import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ExplanationView } from './ExplanationView';

describe('ExplanationView', () => {
  it('starts a Teacher Explanation from the idle state', async () => {
    const onGenerate = vi.fn();

    render(
      <ExplanationView
        status="idle"
        text=""
        groundingMaterials={[]}
        error={null}
        replay={false}
        onGenerate={onGenerate}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '生成教师讲解' }));

    expect(onGenerate).toHaveBeenCalledOnce();
  });

  it('shows the Grounding Material citations when the explanation completes', () => {
    render(
      <ExplanationView
        status="complete"
        text="这句话的主语是第三人称单数，谓语需要加 -s。"
        groundingMaterials={[
          {
            reference: 1,
            category: 'GRAMMAR',
            content: '主谓一致：主语是第三人称单数时，现在时的谓语动词要加 -s。',
            source: 'WriteLab 自建依据材料',
            license: 'CC BY 4.0',
          },
        ]}
        error={null}
        replay={false}
        onGenerate={() => undefined}
      />,
    );

    expect(screen.getByText('本次依据')).toBeInTheDocument();
    expect(screen.getByText('依据1')).toBeInTheDocument();
    expect(screen.getByText('主谓一致：主语是第三人称单数时，现在时的谓语动词要加 -s。')).toBeInTheDocument();
  });
});
