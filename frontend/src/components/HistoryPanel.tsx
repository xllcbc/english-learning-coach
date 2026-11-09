import { formatTimestamp } from '../format';
import { levelDescriptions } from '../labels';
import type { PracticeSummary } from '../types';

export type HistoryStatus = 'loading' | 'ready' | 'error';

interface HistoryPanelProps {
  summaries: PracticeSummary[];
  status: HistoryStatus;
  selectedId: string | null;
  collapsed: boolean;
  onSelect: (id: string) => void;
  onRetry: () => void;
  onToggle: () => void;
}

export function HistoryPanel({
  summaries,
  status,
  selectedId,
  collapsed,
  onSelect,
  onRetry,
  onToggle,
}: HistoryPanelProps) {
  return (
    <>
      <button
        type="button"
        className="history-toggle"
        aria-expanded={!collapsed}
        aria-controls="practice-history"
        onClick={onToggle}
      >
        <span>练习历史</span>
        <span className="history-toggle-meta">
          <span className={`history-toggle-count${status === 'error' ? ' is-error' : ''}`}>
            {status === 'loading'
              ? '加载中…'
              : status === 'error'
                ? '加载失败'
                : `${summaries.length} 次`}
          </span>
          <span aria-hidden="true">{collapsed ? '展开' : '收起'}</span>
        </span>
      </button>

      <aside
        id="practice-history"
        className={`panel history-panel${collapsed ? ' is-collapsed' : ''}`}
        aria-labelledby="history-title"
      >
        <div className="panel-heading history-heading">
          <div>
            <p className="eyebrow">Practice Attempt</p>
            <h2 id="history-title">练习历史</h2>
          </div>
          {status === 'ready' && summaries.length > 0 && (
            <span className="count-pill">{summaries.length} 次</span>
          )}
        </div>

        {status === 'loading' && (
          <p className="history-status" role="status">
            正在加载历史记录…
          </p>
        )}

        {status === 'error' && (
          <div className="history-status history-error" role="alert">
            <p>历史记录加载失败，请稍后重试。</p>
            <button type="button" className="inline-retry" onClick={onRetry}>
              重试
            </button>
          </div>
        )}

        {status === 'ready' && summaries.length === 0 && (
          <p className="history-status">
            还没有练习记录。提交写作练习后，这里会按时间倒序展示每次 Practice Attempt。
          </p>
        )}

        {status === 'ready' && summaries.length > 0 && (
          <ul className="history-list">
            {summaries.map((summary) => (
              <li key={summary.id}>
                <button
                  type="button"
                  className="history-item"
                  aria-current={selectedId === summary.id ? 'true' : undefined}
                  onClick={() => onSelect(summary.id)}
                >
                  <span className="history-item-date">{formatTimestamp(summary.createdAt)}</span>
                  <span className="history-item-meta">
                    <strong>{summary.estimatedLevel}</strong>
                    <span>{levelDescriptions[summary.estimatedLevel]}</span>
                    <span>{summary.wordCount} 词</span>
                  </span>
                  <span className="history-preview">{summary.preview}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>
    </>
  );
}
