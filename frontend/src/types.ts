export type EstimatedLevel = 'A2' | 'B1' | 'B2' | 'C1';

export type IssueCategory =
  | 'GRAMMAR'
  | 'VOCABULARY'
  | 'SENTENCE_STRUCTURE'
  | 'COHERENCE'
  | 'PUNCTUATION';

export type IssueSeverity = 'MINOR' | 'MAJOR';

export type ExplanationStatus = 'idle' | 'streaming' | 'complete' | 'error';

export interface Scores {
  grammar: number;
  vocabulary: number;
  coherence: number;
}

export interface LearningIssue {
  originalSentence: string;
  suggestion: string;
  category: IssueCategory;
  severity: IssueSeverity;
  explanation: string;
}

export interface CorpusSentenceSource {
  source: string;
  license: string;
}

export interface VocabularySuggestion {
  word: string;
  explanation: string;
  example: string;
  corpusSentenceSource?: CorpusSentenceSource | null;
}

export interface StructuredFeedback {
  correction: string;
  naturalRevision: string;
  estimatedLevel: EstimatedLevel;
  scores: Scores;
  strengths: string[];
  learningIssues: LearningIssue[];
  vocabularySuggestions: VocabularySuggestion[];
}

export interface GroundingCitation {
  reference: number;
  category: IssueCategory;
  content: string;
  source: string;
  license: string;
}

export interface DocumentCitation {
  reference: number;
  documentId: string | null;
  documentChunkId: string | null;
  title: string;
  content: string;
  deleted: boolean;
}

export type ToolInvocationStatus = 'SUCCESS' | 'NOT_FOUND' | 'BLOCKED' | 'FAILED';


export interface ToolInvocation {
  toolName: string;
  arguments: Record<string, string>;
  status: ToolInvocationStatus;
  durationMillis: number;
}

export interface PracticeResponse {
  id: string;
  writingText: string;
  wordCount: number;
  modelMode: 'MOCK' | 'REAL';
  mockMode: boolean;
  hasExplanation: boolean;
  feedback: StructuredFeedback;
  toolInvocations: ToolInvocation[];
  createdAt: string;
}

export interface PracticeSummary {
  id: string;
  createdAt: string;
  wordCount: number;
  estimatedLevel: EstimatedLevel;
  modelMode: 'MOCK' | 'REAL';
  mockMode: boolean;
  preview: string;
}

export interface ApiError {
  code: string;
  message: string;
}

export type UserDocumentStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'READY'
  | 'FAILED';

export type UserDocumentReplacementStatus = 'NONE' | 'PROCESSING' | 'FAILED';

export interface UserDocumentSummary {
  id: string;
  filename: string;
  status: UserDocumentStatus;
  active: boolean;
  charCount: number;
  chunkCount: number;
  embeddingIndexed: boolean;
  embeddingModel: string | null;
  chunkerVersion: string;
  errorCode: string | null;
  replacementStatus: UserDocumentReplacementStatus;
  replacementErrorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserDocumentUploadResponse {
  id: string;
  status: UserDocumentStatus;
}

export interface DictionaryEntry {
  word: string;
  phonetic: string;
  partOfSpeech: string;
  meaningZh: string;
  exampleEn: string;
}

export interface SavedVocabularyEntry extends DictionaryEntry {
  id: string;
  createdAt: string;
}
