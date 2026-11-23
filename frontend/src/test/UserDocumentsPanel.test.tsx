import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { UserDocumentsPanel } from '../components/UserDocumentsPanel';
import type { UserDocumentSummary } from '../types';

const api = vi.hoisted(() => ({
  listUserDocuments: vi.fn(),
  uploadUserDocument: vi.fn(),
}));

vi.mock('../api', () => ({
  listUserDocuments: api.listUserDocuments,
  uploadUserDocument: api.uploadUserDocument,
  toErrorMessage: (error: unknown) =>
    error instanceof Error ? error.message : '无法连接后端服务，请确认服务已启动。',
}));

const parsingDocument: UserDocumentSummary = {
  id: 'doc-1',
  filename: 'grammar.md',
  status: 'PARSING',
  active: true,
  charCount: 1200,
  chunkCount: 0,
  errorCode: null,
  createdAt: '2026-11-23T08:00:00Z',
  updatedAt: '2026-11-23T08:00:00Z',
};

const readyDocument: UserDocumentSummary = {
  ...parsingDocument,
  status: 'READY',
  chunkCount: 3,
  updatedAt: '2026-11-23T08:00:02Z',
};

const failedDocument: UserDocumentSummary = {
  ...parsingDocument,
  id: 'doc-2',
  filename: 'broken.txt',
  status: 'FAILED',
  errorCode: 'PROCESSING_FAILED',
  updatedAt: '2026-11-23T08:00:05Z',
};

describe('UserDocumentsPanel', () => {
  beforeEach(() => {
    api.listUserDocuments.mockReset();
    api.uploadUserDocument.mockReset();
  });

  it('shows the upload hints and an empty state when there are no documents', async () => {
    api.listUserDocuments.mockResolvedValue([]);

    render(<UserDocumentsPanel />);

    expect(await screen.findByText('还没有上传任何文档')).toBeInTheDocument();
    expect(screen.getByText('仅支持 TXT/Markdown、UTF-8')).toBeInTheDocument();
    expect(screen.getByText('请勿上传敏感信息')).toBeInTheDocument();
    expect(screen.getByText('0 篇')).toBeInTheDocument();
  });

  it('lists each document with its status and chunk count in the given order', async () => {
    const older: UserDocumentSummary = {
      ...readyDocument,
      id: 'doc-older',
      filename: 'older.txt',
      chunkCount: 12,
    };
    api.listUserDocuments.mockResolvedValue([readyDocument, older]);

    render(<UserDocumentsPanel />);

    const list = await screen.findByRole('list', { name: '我的文档列表' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('grammar.md')).toBeInTheDocument();
    expect(within(items[0]).getByText('已就绪')).toBeInTheDocument();
    expect(within(items[0]).getByText('3 个分块')).toBeInTheDocument();
    expect(within(items[1]).getByText('older.txt')).toBeInTheDocument();
    expect(within(items[1]).getByText('12 个分块')).toBeInTheDocument();
    expect(screen.getByText('2 篇')).toBeInTheDocument();
  });

  it('uploads a document and shows it immediately in the panel', async () => {
    api.listUserDocuments.mockResolvedValueOnce([]).mockResolvedValue([readyDocument]);
    api.uploadUserDocument.mockResolvedValue({ id: 'doc-1', status: 'UPLOADED' });

    render(<UserDocumentsPanel />);
    await screen.findByText('还没有上传任何文档');

    const file = new File(['# Grammar'], 'grammar.md', { type: 'text/markdown' });
    await userEvent.upload(screen.getByLabelText('选择 TXT 或 Markdown 文件'), file);
    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    expect(api.uploadUserDocument).toHaveBeenCalledWith(file);
    expect(await screen.findByText('grammar.md')).toBeInTheDocument();
    expect(screen.getByText('3 个分块')).toBeInTheDocument();
  });

  it('polls processing documents until READY and then stops', async () => {
    api.listUserDocuments
      .mockResolvedValueOnce([parsingDocument])
      .mockResolvedValueOnce([readyDocument])
      .mockResolvedValue([{ ...readyDocument, status: 'FAILED', errorCode: 'PROCESSING_FAILED' }]);

    render(<UserDocumentsPanel pollIntervalMs={10} />);

    expect(await screen.findByText('解析中')).toBeInTheDocument();
    expect(await screen.findByText('已就绪')).toBeInTheDocument();

    await new Promise((resolve) => setTimeout(resolve, 60));

    // A continued poll would have replaced the terminal READY state with the later FAILED response.
    expect(screen.getByText('已就绪')).toBeInTheDocument();
    expect(screen.queryByText('处理失败')).not.toBeInTheDocument();
  });

  it('shows a stable failure reason when processing fails', async () => {
    api.listUserDocuments.mockResolvedValue([failedDocument]);

    render(<UserDocumentsPanel />);

    expect(await screen.findByText('处理失败')).toBeInTheDocument();
    expect(screen.getByText('失败原因：文档处理失败，请稍后重试。')).toBeInTheDocument();
    expect(screen.getByText('错误码：PROCESSING_FAILED')).toBeInTheDocument();
  });

  it('explains a duplicate upload and refreshes the existing document', async () => {
    api.listUserDocuments.mockResolvedValueOnce([]).mockResolvedValue([readyDocument]);
    api.uploadUserDocument.mockRejectedValue(
      Object.assign(new Error('相同内容的文档已经存在。'), { code: 'DUPLICATE_DOCUMENT' }),
    );

    render(<UserDocumentsPanel />);
    await screen.findByText('还没有上传任何文档');

    const file = new File(['# Grammar'], 'grammar.md', { type: 'text/markdown' });
    await userEvent.upload(screen.getByLabelText('选择 TXT 或 Markdown 文件'), file);
    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      '已有相同内容的文档，已返回原文档。',
    );
    expect(await screen.findByText('grammar.md')).toBeInTheDocument();
  });

  it('shows an understandable message when the upload request fails', async () => {
    api.listUserDocuments.mockResolvedValue([]);
    api.uploadUserDocument.mockRejectedValue(new Error('网络断开，请重试。'));

    render(<UserDocumentsPanel />);
    await screen.findByText('还没有上传任何文档');

    const file = new File(['# Grammar'], 'grammar.md', { type: 'text/markdown' });
    await userEvent.upload(screen.getByLabelText('选择 TXT 或 Markdown 文件'), file);
    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('网络断开，请重试。');
  });
});
