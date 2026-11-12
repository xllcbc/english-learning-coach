// Builds the bundled Corpus Sentence dataset: up to twelve real Tatoeba sentences for
// every word form in the shipped Dictionary Entry dataset (CC-BY 2.0 FR). The dictionary
// CSV is the single source of truth for "core word + word-form expansion", so this script
// cannot drift from docs/dictionary.md. The generated CSV is committed and read by
// CorpusSentenceImporter. Manual tool only: it is not wired into Maven or CI.
//
//   node tools/build-corpus.mjs <dictionary.csv> <eng_sentences.tsv> [out.csv]
//
// eng_sentences.tsv is the detailed per-language export:
//   https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences_detailed.tsv.bz2
// decompressed with `bunzip2 -c eng_sentences_detailed.tsv.bz2 > eng_sentences.tsv`.
//
// Deterministic output: candidates are filtered to 6-18 tokens, sorted by token count then
// sentence text, and capped per word form. Upstream exports change between downloads, so a
// rerun is a new curation, not a reproducible build.

import fs from 'node:fs';
import path from 'node:path';

const MAX_SENTENCES_PER_WORD = 12;
const MIN_WORDS = 6;
const MAX_WORDS = 18;
const LICENSE = 'CC BY 2.0 FR';

const [,, dictionaryPath, sentencesPath, outPath = path.join(
  'backend', 'src', 'main', 'resources', 'corpus', 'sentences.csv'
)] = process.argv;
if (!dictionaryPath || !sentencesPath) {
  console.error('usage: node tools/build-corpus.mjs <dictionary.csv> <eng_sentences.tsv> [out.csv]');
  process.exit(1);
}

function csvFields(line) {
  const fields = [];
  let current = '';
  let quoted = false;
  for (let index = 0; index < line.length; index++) {
    const character = line[index];
    if (quoted) {
      if (character === '"' && line[index + 1] === '"') { current += '"'; index++; }
      else if (character === '"') quoted = false;
      else current += character;
    } else if (character === '"') quoted = true;
    else if (character === ',') { fields.push(current); current = ''; }
    else current += character;
  }
  fields.push(current);
  return fields;
}

function readWordForms(file) {
  const words = new Set();
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!line || line.startsWith('word,')) continue;
    const word = (csvFields(line)[0] ?? '').trim().toLowerCase();
    if (word) words.add(word);
  }
  return words;
}

const tokensOf = (text) => text.toLowerCase().match(/[a-z0-9][a-z0-9_'-]*/g) ?? [];

// A word form counts as contained when it is the whole token, or one side of an
// apostrophe/hyphen inside it ("chan's" contains "chan", but "4chan" does not).
function matchableTokens(tokens) {
  const candidates = new Set(tokens);
  for (const token of tokens) {
    if (token.includes("'") || token.includes('-')) {
      for (const part of token.split(/['-]/)) {
        if (part) candidates.add(part);
      }
    }
  }
  return candidates;
}

function acceptable(text) {
  if (!/^[\x20-\x7E]+$/.test(text)) return false;            // ASCII only keeps word boundaries unambiguous
  if (text.includes('"')) return false;                      // same rule as the dictionary example filter
  if (!/^[A-Z]/.test(text)) return false;
  if (text !== text.trim() || text.includes('  ')) return false;
  const count = tokensOf(text).length;
  return count >= MIN_WORDS && count <= MAX_WORDS;
}

const compare = (left, right) => left.wordCount - right.wordCount
  || (left.text < right.text ? -1 : left.text > right.text ? 1 : 0);

function offer(sentencesByWordForm, wordForm, candidate) {
  let list = sentencesByWordForm.get(wordForm);
  if (!list) {
    list = [];
    sentencesByWordForm.set(wordForm, list);
  }
  const key = candidate.text.toLowerCase();
  if (list.some((existing) => existing.text.toLowerCase() === key)) return;
  let index = list.length;
  while (index > 0 && compare(candidate, list[index - 1]) < 0) index--;
  if (index >= MAX_SENTENCES_PER_WORD) return;
  list.splice(index, 0, candidate);
  if (list.length > MAX_SENTENCES_PER_WORD) list.pop();
}

function collect(sentencesPath, wordForms) {
  const sentencesByWordForm = new Map();
  let scanned = 0;
  let pending = '';
  const fd = fs.openSync(sentencesPath, 'r');
  const buffer = Buffer.alloc(1 << 20);
  const handle = (raw) => {
    const columns = raw.split('\t');
    if (columns.length < 4) return;                          // id, lang, text, username, dates...
    scanned++;
    const text = columns[2];
    if (!text || !acceptable(text)) return;
    const username = columns[3] && columns[3] !== '\\N' ? columns[3] : '';
    const words = tokensOf(text);
    for (const token of matchableTokens(words)) {
      if (wordForms.has(token)) offer(sentencesByWordForm, token, { text, wordCount: words.length, username });
    }
  };
  for (;;) {
    const read = fs.readSync(fd, buffer, 0, buffer.length, null);
    if (read === 0) break;
    pending += buffer.toString('utf8', 0, read);
    const lines = pending.split('\n');
    pending = lines.pop();
    for (const line of lines) handle(line.replace(/\r$/, ''));
  }
  fs.closeSync(fd);
  return { sentencesByWordForm, scanned };
}

const wordForms = readWordForms(dictionaryPath);
console.log(`dictionary=${dictionaryPath} wordForms=${wordForms.size}`);

const { sentencesByWordForm, scanned } = collect(sentencesPath, wordForms);

const quote = (value) => `"${String(value).replaceAll('"', '""')}"`;
const rows = [];
for (const wordForm of [...wordForms].sort()) {
  for (const sentence of sentencesByWordForm.get(wordForm) ?? []) {
    const source = sentence.username ? `Tatoeba / ${sentence.username}` : 'Tatoeba';
    rows.push([wordForm, sentence.text, source, LICENSE]);
  }
}

const csv = ['word_form,sentence,source,license'];
for (const row of rows) csv.push(row.map(quote).join(','));
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, `${csv.join('\n')}\n`, 'utf8');

const covered = [...sentencesByWordForm.keys()].length;
const largest = Math.max(0, ...[...sentencesByWordForm.values()].map((list) => list.length));
console.log(`scanned=${scanned} coveredWordForms=${covered} rows=${rows.length}`
  + ` maxPerWord=${largest} out=${outPath}`);
