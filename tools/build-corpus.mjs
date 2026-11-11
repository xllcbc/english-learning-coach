// Curates the bundled Corpus Sentence dataset from Tatoeba's public search API
// (CC-BY 2.0 FR). Only the generated CSV ships; this helper is run by hand when the
// word list changes and is deliberately not wired into Maven or CI.
//
//   node tools/build-corpus.mjs [out.csv]
//
// Deterministic output: sentences are filtered to 6-18 words, sorted by word count
// then sentence text, and capped per word form. Upstream search results can change,
// so a rerun is a new curation, not a reproducible build.

import fs from 'node:fs';
import path from 'node:path';

const WORD_FORMS = [
  'many',
  'moreover',
  'consequently',
  'effective',
  'particularly',
  'improve',
  'benefit',
  'practice',
  'focused',
  'writing',
];

const SENTENCES_PER_WORD = 4;
const MAX_PAGES = 6;
const MIN_WORDS = 6;
const MAX_WORDS = 18;
const LICENSE = 'CC BY 2.0 FR';

const outPath = process.argv[2] ?? path.join(
  'backend', 'src', 'main', 'resources', 'corpus', 'sentences.csv'
);

function words(text) {
  return text.trim().split(/\s+/).filter(Boolean);
}

function containsWordForm(text, wordForm) {
  return new RegExp(`\\b${wordForm.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i').test(text);
}

function acceptable(sentence, wordForm) {
  if (!/^[\x20-\x7E]+$/.test(sentence)) return false;         // printable ASCII only
  if (sentence !== sentence.trim() || sentence.includes('  ')) return false;
  if (!containsWordForm(sentence, wordForm)) return false;
  const count = words(sentence).length;
  return count >= MIN_WORDS && count <= MAX_WORDS;
}

function quote(value) {
  return `"${value.replaceAll('"', '""')}"`;
}

async function search(wordForm, page) {
  const url = new URL('https://tatoeba.org/en/api_v0/search');
  url.searchParams.set('query', wordForm);
  url.searchParams.set('from', 'eng');
  url.searchParams.set('orphans', 'no');
  url.searchParams.set('unapproved', 'no');
  url.searchParams.set('sort', 'relevance');
  url.searchParams.set('trans_filter', 'limit');
  url.searchParams.set('word_count_min', String(MIN_WORDS));
  url.searchParams.set('word_count_max', String(MAX_WORDS));
  url.searchParams.set('page', String(page));
  const response = await fetch(url, { headers: { 'User-Agent': 'WriteLab-corpus/0.1' } });
  if (!response.ok) {
    throw new Error(`Tatoeba search failed for ${wordForm}: HTTP ${response.status}`);
  }
  return response.json();
}

async function collect(wordForm) {
  const byText = new Map();
  for (let page = 1; page <= MAX_PAGES; page++) {
    const payload = await search(wordForm, page);
    for (const result of payload.results ?? []) {
      if (result.license !== LICENSE) continue;
      if (!acceptable(result.text, wordForm)) continue;
      const key = result.text.toLowerCase();
      if (!byText.has(key)) {
        byText.set(key, result);
      }
    }
    if (!payload.paging?.Sentences?.nextPage) break;
  }
  return [...byText.values()]
    .sort((left, right) => words(left.text).length - words(right.text).length
      || (left.text < right.text ? -1 : left.text > right.text ? 1 : 0))
    .slice(0, SENTENCES_PER_WORD);
}

const rows = [];
for (const wordForm of WORD_FORMS) {
  const sentences = await collect(wordForm);
  console.log(`${wordForm}: ${sentences.length}`);
  for (const sentence of sentences) {
    const author = sentence.user?.username ? `Tatoeba / ${sentence.user.username}` : 'Tatoeba';
    rows.push([wordForm, sentence.text, author, LICENSE]);
  }
}

const csv = ['word_form,sentence,source,license'];
for (const row of rows) {
  csv.push(row.map(quote).join(','));
}
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, `${csv.join('\n')}\n`, 'utf8');
console.log(`wrote ${rows.length} rows to ${outPath}`);
