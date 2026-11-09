const TOKEN_PATTERN = /[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*/gu;

export const MIN_WORDS = 200;
export const MAX_WORDS = 500;

export function countWords(text: string): number {
  return text.match(TOKEN_PATTERN)?.length ?? 0;
}
