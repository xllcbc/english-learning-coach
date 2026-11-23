import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';

import {
  deleteUserDocument,
  listUserDocuments,
  retryUserDocument,
  setUserDocumentActive,
  toErrorMessage,
  uploadUserDocument,
} from '../api';
import type { UserDocumentStatus, UserDocumentSummary } from '../types';

const PROCESSING_STATUSES = new Set<UserDocumentStatus>([
  'UPLOADED',
  'PARSING',
  'CHUNKING',
  'EMBEDDING',
]);

const STATUS_LABELS: Record<UserDocumentStatus, string> = {
  UPLOADED: '已上传',
  PARSING: '解析中',
  CHUNKING: '分块中',
  EMBEDDING: '建立索引中',
  READY: '已就绪',
  FAILED: '处理失败',
};

const FAILURE_MESSAGES: Record<string, string> = {
  PROCESSING_FAILED: '文档处理失败，请稍后重试。',
  EMPTY_DOCUMENT: '文档内容为空或只有空白字符。',
  INVALID_ENCODING: '文档不是有效的 UTF-8 文本。',
  BINARY_DOCUMENT: '文档看起来是二进制内容，无法作为文本处理。',
  CHUNK_LIMIT_REACHED: '文档分块数量超出上限，请拆分后重新上传。',
};

interface UserDocumentsPanelProps {
  pollIntervalMs?: number;
}

function failureMessage(errorCode: string | null): string {
  return FAILURE_MESSAGES[errorCode ?? ''] ?? '文档处理失败，请稍后重试。';
}

export function UserDocumentsPanel({ pollIntervalMs = 1500 }: UserDocumentsPanelProps) {
  const [documents, setDocuments] = useState<UserDocumentSummary[]>([]);
  const [listStatus, setListStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [listError, setListError] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyDocumentId, setBusyDocumentId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const loadDocuments = useCallback(async () => {
    try {
      const loaded = await listUserDocuments();
      setDocuments(loaded);
      setListStatus('ready');
      setListError(null);
    } catch (error) {
      setListStatus('error');
      setListError(toErrorMessage(error));
    }
  }, []);

  useEffect(() => {
    void loadDocuments();
  }, [loadDocuments]);

  const hasProcessingDocument = documents.some(
    (document) =>
      PROCESSING_STATUSES.has(document.status)
      || document.replacementStatus === 'PROCESSING',
  );

  useEffect(() => {
    if (!hasProcessingDocument) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      void loadDocuments();
    }, pollIntervalMs);
    return () => window.clearInterval(timer);
  }, [hasProcessingDocument, loadDocuments, pollIntervalMs]);

  function replaceDocument(updated: UserDocumentSummary) {
    setDocuments((current) =>
      current.map((item) => (item.id === updated.id ? updated : item)),
    );
  }

  async function runDocumentAction(
    document: UserDocumentSummary,
    action: () => Promise<void>,
  ) {
    setBusyDocumentId(document.id);
    setActionError(null);
    setNotice(null);
    try {
      await action();
    } catch (error) {
      setActionError(toErrorMessage(error));
    } finally {
      setBusyDocumentId(null);
    }
  }

  async function handleToggleActive(document: UserDocumentSummary) {
    await runDocumentAction(document, async () => {
      const updated = await setUserDocumentActive(document.id, !document.active);
      replaceDocument(updated);
    });
  }

  async function handleRetry(document: UserDocumentSummary) {
    await runDocumentAction(document, async () => {
      const updated = await retryUserDocument(document.id);
      replaceDocument(updated);
      await loadDocuments();
    });
  }

  async function handleDelete(document: UserDocumentSummary) {
    const confirmed = window.confirm(
      `确定删除文档“${document.filename}”？删除后无法恢复。`,
    );
    if (!confirmed) {
      return;
    }

    await runDocumentAction(document, async () => {
      await deleteUserDocument(document.id);
      setDocuments((current) => current.filter((item) => item.id !== document.id));
      setNotice(`已删除文档“${document.filename}”。`);
    });
  }

  async function handleUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedFile || uploading) {
      return;
    }

    setUploading(true);
    setUploadError(null);
    setNotice(null);
    try {
      await uploadUserDocument(selectedFile);
      setSelectedFile(null);
      await loadDocuments();
    } catch (error) {
      if (error instanceof Error && (error as { code?: string }).code === 'DUPLICATE_DOCUMENT') {
        setNotice('已有相同内容的文档，已返回原文档。');
        await loadDocuments();
      } else {
        setUploadError(toErrorMessage(error));
      }
    } finally {
      setUploading(false);
    }
  }

  return (
    <section className="panel user-documents-panel" aria-labelledby="user-documents-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">User Documents</p>
          <h2 id="user-documents-title">我的文档</h2>
        </div>
        <span className="count-pill">{documents.length} 篇</span>
      </div>

      <form className="document-upload" onSubmit={handleUpload}>
        <div className="document-upload-row">
          <label className="document-file-label" htmlFor="document-file-input">
            选择 TXT 或 Markdown 文件
          </label>
          <input
            id="document-file-input"
            className="document-file-input"
            type="file"
            accept=".md,.txt,text/markdown,text/plain"
            onChange={(event) => {
              setSelectedFile(event.target.files?.[0] ?? null);
              setUploadError(null);
            }}
          />
          <button
            type="submit"
            className="submit-button document-upload-button"
            disabled={!selectedFile || uploading}
          >
            {uploading ? '正在上传…' : '上传文档'}
          </button>
        </div>
        <p className="document-upload-hint">仅支持 TXT/Markdown、UTF-8</p>
        <p className="document-upload-hint">请勿上传敏感信息</p>
      </form>

      {notice && (
        <div className="document-notice" role="status">
          {notice}
        </div>
      )}

      {uploadError && (
        <div className="panel-error" role="alert">
          <p>{uploadError}</p>
        </div>
      )}

      {actionError && (
        <div className="panel-error" role="alert">
          <p>{actionError}</p>
        </div>
      )}

      {listStatus === 'loading' && (
        <p className="muted" role="status">
          正在加载我的文档…
        </p>
      )}

      {listStatus === 'error' && (
        <div className="panel-error" role="alert">
          <p>{listError ?? '加载我的文档失败，请稍后重试。'}</p>
          <button type="button" className="text-button" onClick={() => void loadDocuments()}>
            重新加载
          </button>
        </div>
      )}

      {listStatus === 'ready' && documents.length === 0 && (
        <div className="saved-empty document-empty">
          <h3>还没有上传任何文档</h3>
          <p>上传 TXT 或 Markdown 笔记后，教师讲解可以引用它们。</p>
        </div>
      )}

      {documents.length > 0 && (
        <ul className="document-list" aria-label="我的文档列表">
          {documents.map((document) => (
            <li key={document.id} className="document-item">
              <div className="document-heading">
                <span className="document-name">{document.filename}</span>
                <span className={`document-status document-status-${document.status.toLowerCase()}`}>
                  {STATUS_LABELS[document.status]}
                </span>
                {document.replacementStatus === 'PROCESSING' && (
                  <span className="document-status document-status-updating">替换中</span>
                )}
                {document.replacementStatus === 'FAILED' && (
                  <span className="document-status document-status-failed">替换失败</span>
                )}
              </div>
              <p className="document-meta">
                {document.chunkCount} 个分块
                {!document.active && <span className="document-inactive">已停用</span>}
              </p>
              <div className="document-actions">
                {document.status === 'READY' && (
                  <button
                    type="button"
                    className="text-button"
                    disabled={busyDocumentId === document.id}
                    onClick={() => void handleToggleActive(document)}
                  >
                    {document.active ? '停用' : '启用'}
                  </button>
                )}
                {(document.status === 'FAILED'
                  || document.replacementStatus === 'FAILED') && (
                  <button
                    type="button"
                    className="text-button"
                    disabled={busyDocumentId === document.id}
                    onClick={() => void handleRetry(document)}
                  >
                    重试
                  </button>
                )}
                <button
                  type="button"
                  className="text-button text-button-danger"
                  disabled={busyDocumentId === document.id}
                  onClick={() => void handleDelete(document)}
                >
                  删除
                </button>
              </div>
              {document.status === 'FAILED' && (
                <div className="document-failure" role="note">
                  <p>失败原因：{failureMessage(document.errorCode)}</p>
                  <p>错误码：{document.errorCode ?? 'PROCESSING_FAILED'}</p>
                </div>
              )}
              {document.replacementStatus === 'FAILED' && (
                <div className="document-failure" role="note">
                  <p>替换失败原因：{failureMessage(document.replacementErrorCode)}</p>
                  <p>错误码：{document.replacementErrorCode ?? 'PROCESSING_FAILED'}</p>
                  <p>旧内容仍然可用，可以重试替换。</p>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
