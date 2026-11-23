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
        documentCitations={[]}
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
        documentCitations={[]}
        error={null}
        replay={false}
        onGenerate={() => undefined}
      />,
    );

    expect(screen.getByText('本次依据')).toBeInTheDocument();
    expect(screen.getByText('依据1')).toBeInTheDocument();
    expect(screen.getByText('规则')).toBeInTheDocument();
    expect(screen.getByText('主谓一致：主语是第三人称单数时，现在时的谓语动词要加 -s。')).toBeInTheDocument();
  });

  it('lists Grounding Material and User Document citations in one reference order', () => {
    render(
      <ExplanationView
        status="complete"
        text="讲解正文 [依据1] [依据2]"
        groundingMaterials={[
          {
            reference: 1,
            category: 'GRAMMAR',
            content: '规则内容。',
            source: 'WriteLab 自建依据材料',
            license: 'CC BY 4.0',
          },
        ]}
        documentCitations={[
          {
            reference: 2,
            documentId: 'document-1',
            documentChunkId: 'chunk-1',
            title: 'grammar.md · Grammar > Subject-verb agreement',
            content: 'I have a problem with this sentence.',
            deleted: false,
          },
        ]}
        error={null}
        replay={false}
        onGenerate={() => undefined}
      />,
    );

    const references = screen.getAllByText(/^依据\d+$/).map((element) => element.textContent);
    expect(references).toEqual(['依据1', '依据2']);
    expect(screen.getByText('我的文档')).toBeInTheDocument();
    expect(screen.getByText('grammar.md')).toBeInTheDocument();
    expect(screen.getByText('Grammar > Subject-verb agreement')).toBeInTheDocument();
    expect(screen.getByText('I have a problem with this sentence.')).toBeInTheDocument();
    expect(screen.queryByText('文档已删除')).not.toBeInTheDocument();
  });

  it('marks a document citation whose document was deleted', () => {
    render(
      <ExplanationView
        status="complete"
        text="讲解正文 [依据1]"
        groundingMaterials={[]}
        documentCitations={[
          {
            reference: 1,
            documentId: null,
            documentChunkId: null,
            title: 'grammar.md · Grammar > Subject-verb agreement',
            content: 'I have a problem with this sentence.',
            deleted: true,
          },
        ]}
        error={null}
        replay
        onGenerate={() => undefined}
      />,
    );

    expect(screen.getByText('文档已删除')).toBeInTheDocument();
    expect(screen.getByText('grammar.md')).toBeInTheDocument();
    expect(screen.getByText('Grammar > Subject-verb agreement')).toBeInTheDocument();
    expect(screen.getByText('I have a problem with this sentence.')).toBeInTheDocument();
  });

  it('shows no citation list when neither corpus returned anything', () => {
    render(
      <ExplanationView
        status="complete"
        text="这次写作练习暂时没有发现明显的语言问题。"
        groundingMaterials={[]}
        documentCitations={[]}
        error={null}
        replay={false}
        onGenerate={() => undefined}
      />,
    );

    expect(screen.queryByText('本次依据')).not.toBeInTheDocument();
    expect(screen.queryByText('依据：无')).not.toBeInTheDocument();
  });
});
