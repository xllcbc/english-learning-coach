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

export interface VocabularySuggestion {
  word: string;
  explanation: string;
  example: string;
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
  feedback: StructuredFeedback;
  toolInvocations: ToolInvocation[];
  createdAt: string;
}

export interface ApiError {
  code: string;
  message: string;
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
