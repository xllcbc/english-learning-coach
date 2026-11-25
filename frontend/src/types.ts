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

export type AgentStepKind = 'TOOL_CALL' | 'ANSWER';

export type AgentStepStatus = 'SUCCESS' | 'NOT_FOUND' | 'BLOCKED' | 'FAILED' | 'UNKNOWN_TOOL';

export type AgentStopReason =
  | 'COMPLETED'
  | 'STEP_BUDGET_EXHAUSTED'
  | 'TOOL_BUDGET_EXHAUSTED';

export type ConversationCitationKind =
  | 'GROUNDING_MATERIAL'
  | 'DICTIONARY_ENTRY'
  | 'USER_DOCUMENT';

export interface AgentStep {
  position: number;
  kind: AgentStepKind;
  toolName: string | null;
  arguments: Record<string, unknown>;
  status: AgentStepStatus;
  output: string;
  durationMillis: number;
}

export interface ConversationCitation {
  reference: number;
  kind: ConversationCitationKind;
  title: string;
  content: string;
  source: string;
  license: string | null;
}

export interface TeacherConversationTurn {
  id: string;
  position: number;
  learnerMessage: string;
  answer: string;
  stopReason: AgentStopReason;
  modelMode: 'MOCK' | 'REAL';
  createdAt: string;
  steps: AgentStep[];
  citations: ConversationCitation[];
  toolInvocations: ToolInvocation[];
}

export interface TeacherConversation {
  practiceAttemptId: string;
  conversationId: string | null;
  modelMode: 'MOCK' | 'REAL';
  mockMode: boolean;
  turns: TeacherConversationTurn[];
}

export type SpeakingInputMode = 'VOICE' | 'TEXT';

export type SpeakingFallbackReason = 'failed' | 'timeout' | 'synthesis_failed';

export interface SpeakingTurn {
  id: string;
  sessionId: string;
  position: number;
  inputMode: SpeakingInputMode;
  transcript: string;
  reply: string;
  fallbackReason: SpeakingFallbackReason | null;
  stopReason: AgentStopReason | null;
  modelMode: 'MOCK' | 'REAL';
  createdAt: string;
  steps: AgentStep[];
  toolInvocations: ToolInvocation[];
}

export interface SpeakingSession {
  id: string;
  modelMode: 'MOCK' | 'REAL';
  mockMode: boolean;
  createdAt: string;
  updatedAt: string;
  turns: SpeakingTurn[];
}

export interface SpeakingSessionSummary {
  id: string;
  modelMode: 'MOCK' | 'REAL';
  mockMode: boolean;
  turnCount: number;
  createdAt: string;
}

export interface SpeakingAudioReply {
  type: 'audio';
  sessionId: string;
  audio: string;
  mimeType: string;
  transcript: string;
  reply: string;
  inputMode: SpeakingInputMode;
  mockMode: boolean;
}

export interface SpeakingTextReply {
  type: 'text';
  sessionId: string;
  text: string;
  fallback: boolean;
  reason: SpeakingFallbackReason;
  transcript: string;
  inputMode: SpeakingInputMode;
  mockMode: boolean;
}

export interface SpeakingChannelError {
  code: string;
  message: string;
}

export type SpeakingServerMessage = SpeakingAudioReply | SpeakingTextReply;



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
