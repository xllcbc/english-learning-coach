import type { EstimatedLevel, IssueCategory, IssueSeverity } from './types';

export const categoryLabels: Record<IssueCategory, string> = {
  GRAMMAR: '语法',
  VOCABULARY: '词汇',
  SENTENCE_STRUCTURE: '句式',
  COHERENCE: '连贯性',
  PUNCTUATION: '标点',
};

export const severityLabels: Record<IssueSeverity, string> = {
  MINOR: '轻微',
  MAJOR: '严重',
};

export const levelDescriptions: Record<EstimatedLevel, string> = {
  A2: '基础水平',
  B1: '进阶水平',
  B2: '中高级水平',
  C1: '高级水平',
};

export const scoreLabels = {
  grammar: '语法',
  vocabulary: '词汇',
  coherence: '连贯性',
} as const;
