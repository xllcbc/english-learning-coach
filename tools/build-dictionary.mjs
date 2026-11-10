// Builds the bundled Dictionary Entry dataset from ECDICT (MIT), optionally
// enriched with Tatoeba example sentences (CC-BY 2.0 FR). The generated CSV is
// committed and read by DictionaryDatasetImporter. Manual tool only: it is not
// wired into the Maven build or CI.
//
//   node tools/build-dictionary.mjs <ecdict.csv> [eng_sentences.tsv] [out.csv]
import fs from 'node:fs';
import path from 'node:path';

const [,, ecdictPath, sentencesPath, outPath = path.join(
  'backend', 'src', 'main', 'resources', 'dictionary', 'ecdict-ielts-common.csv',
)] = process.argv;
if (!ecdictPath) {
  console.error('usage: node tools/build-dictionary.mjs <ecdict.csv> [eng_sentences.tsv] [out.csv]');
  process.exit(1);
}

const EXAM_TAGS = ['zk', 'gk', 'cet4', 'cet6', 'ky', 'toefl', 'ielts', 'gre'];
const FREQUENCY_LIMIT = 20000;
const WORD_PATTERN = /^[a-z][a-z'-]{1,23}$/;
const MAX_MEANING_CHARS = 400;
const MIN_EXAMPLE_WORDS = 6;
const MAX_EXAMPLE_WORDS = 18;

const POS_LABELS = {
  n: '名词', ns: '专有名词', pl: '复数名词', pron: '代词', refp: '反身代词', poss: '物主代词',
  adj: '形容词', a: '形容词', s: '形容词', am: '形容词',
  adv: '副词', ad: '副词', r: '副词',
  v: '动词', vt: '及物动词', vi: '不及物动词', vbl: '动名词', vbn: '过去分词', ppp: '过去分词',
  aux: '助动词', modal: '情态动词', inf: '不定式', ger: '动名词',
  prep: '介词', conj: '连词', int: '感叹词', art: '冠词', num: '数词',
  abbr: '缩写', pref: '前缀', suf: '后缀', comb: '组合形式',
};

function parseCsvStream(file, onRow) {
  const fd = fs.openSync(file, 'r');
  const buffer = Buffer.alloc(1 << 20);
  let pending = '';
  let fields = [];
  let current = '';
  let inQuotes = false;
  let header = null;
  for (;;) {
    const read = fs.readSync(fd, buffer, 0, buffer.length, null);
    if (read === 0) break;
    pending += buffer.toString('utf8', 0, read);
    let index = 0;
    for (; index < pending.length; index++) {
      const char = pending[index];
      if (inQuotes) {
        // DictionaryDatasetImporter reads line by line, so a quoted field must never hold a newline.
        if (char !== '"') current += char === '\n' || char === '\r' ? ' ' : char;
        else if (pending[index + 1] === '"') { current += '"'; index++; }
        else inQuotes = false;
      } else if (char === '"') inQuotes = true;
      else if (char === ',') { fields.push(current); current = ''; }
      else if (char === '\n') {
        fields.push(current);
        current = '';
        const row = fields;
        fields = [];
        if (header === null) header = row;
        else onRow(header, row);
      } else if (char !== '\r') current += char;
    }
    pending = pending.slice(index);
  }
  fs.closeSync(fd);
}

const asNumber = (value) => (/^\d+$/.test(value || '') ? Number(value) : 0);
const hasExamTag = (tag) => EXAM_TAGS.some((exam) => ` ${tag} `.includes(` ${exam} `));
const splitLines = (translation) => translation.split('\\n').map((line) => line.trim()).filter(Boolean);

const hasChinese = (text) => /[一-鿿]/.test(text);
const stripDomainTags = (line) => line.replace(/\[[^\]]{1,8}\]\s?/g, '').trim();

function partOfSpeech(lines) {
  const labels = [];
  for (const line of lines) {
    const match = /^([a-z]+)\./.exec(line);
    const label = match ? POS_LABELS[match[1]] : undefined;
    if (label && !labels.includes(label)) labels.push(label);
  }
  return labels.slice(0, 3).join('/');
}

function meaning(translation) {
  const lines = splitLines(translation)
    .filter((line) => !line.startsWith('['))
    .filter(hasChinese)
    .map(stripDomainTags)
    .filter(hasChinese);
  const text = lines.join('；');
  return text.length > MAX_MEANING_CHARS ? `${text.slice(0, MAX_MEANING_CHARS - 1)}…` : text;
}

function phonetic(raw) {
  const cleaned = (raw || '')
    .trim()
    .replace(/^\[/, '')
    .replace(/\]$/, '')
    .split(/\s*[,;]\s*/)[0]
    .replaceAll('ә', 'ə')
    .replace(/'/g, 'ˈ');
  if (!cleaned || cleaned.length > 62) return '';
  return /^[/∥]/.test(cleaned) ? cleaned : `/${cleaned}/`;
}

const lemmaOf = (exchange) => {
  const match = /(?:^|\/)0:([a-z][a-z'-]*)/.exec(exchange || '');
  return match ? match[1] : null;
};

const isCore = (row) => hasExamTag(row.tag) || asNumber(row.collins) > 0
  || (asNumber(row.frq) > 0 && asNumber(row.frq) <= FREQUENCY_LIMIT)
  || (asNumber(row.bnc) > 0 && asNumber(row.bnc) <= FREQUENCY_LIMIT);

const core = new Map();
const inflections = new Map();
let scanned = 0;

function toEntry(row) {
  return {
    word: row.word,
    phonetic: phonetic(row.phonetic),
    partOfSpeech: partOfSpeech(splitLines(row.translation)),
    meaningZh: meaning(row.translation),
    exampleEn: '',
  };
}

const derived = [];

parseCsvStream(ecdictPath, (header, row) => {
  scanned++;
  const record = {
    word: row[header.indexOf('word')] ?? row[0],
    phonetic: row[header.indexOf('phonetic')] ?? row[1],
    translation: row[header.indexOf('translation')] ?? row[3],
    collins: row[header.indexOf('collins')] ?? row[5],
    tag: row[header.indexOf('tag')] ?? row[7],
    bnc: row[header.indexOf('bnc')] ?? row[8],
    frq: row[header.indexOf('frq')] ?? row[9],
    exchange: row[header.indexOf('exchange')] ?? row[10],
  };
  if (!WORD_PATTERN.test(record.word) || !record.translation.trim()) return;
  const entry = toEntry(record);
  if (!entry.meaningZh) return;
  if (isCore(record)) {
    core.set(record.word, entry);
    return;
  }
  const lemma = lemmaOf(record.exchange);
  if (lemma) derived.push({ word: record.word, lemma, entry });
});

for (const { word, lemma, entry } of derived) {
  if (!core.has(word) && core.has(lemma)) inflections.set(word, entry);
}

const selected = new Map([...core, ...inflections]);
console.log(`scanned=${scanned} core=${core.size} inflections=${inflections.size} total=${selected.size}`);
if (sentencesPath) {
  const best = new Map();
  let pendingText = '';
  const fd = fs.openSync(sentencesPath, 'r');
  const buffer = Buffer.alloc(1 << 20);
  const handle = (raw) => {
    const columns = raw.split('\t');
    // Per-language exports vary between (id, text) and (id, lang, text).
    const text = (columns.length >= 3 ? columns.slice(2).join('\t') : columns[1] ?? '').trim();
    if (!text || text.includes('"') || !/^[A-Z]/.test(text)) return;
    const tokens = text.toLowerCase().match(/[a-z']+/g) ?? [];
    if (tokens.length < MIN_EXAMPLE_WORDS || tokens.length > MAX_EXAMPLE_WORDS) return;
    for (const token of new Set(tokens)) {
      const entry = selected.get(token);
      if (!entry || (best.has(token) && best.get(token).words <= tokens.length)) continue;
      best.set(token, { text, words: tokens.length });
    }
  };
  for (;;) {
    const read = fs.readSync(fd, buffer, 0, buffer.length, null);
    if (read === 0) break;
    pendingText += buffer.toString('utf8', 0, read);
    const lines = pendingText.split('\n');
    pendingText = lines.pop();
    for (const each of lines) handle(each.replace(/\r$/, ''));
  }
  fs.closeSync(fd);
  for (const [word, sentence] of best) selected.get(word).exampleEn = sentence.text;
  console.log(`examples attached=${best.size}`);
}

const csvField = (value) => `"${String(value).replaceAll('"', '""')}"`;
const rowsOut = [...selected.values()]
  .sort((a, b) => (a.word < b.word ? -1 : 1))
  .map((entry) => [entry.word, entry.phonetic, entry.partOfSpeech, entry.meaningZh, entry.exampleEn]
    .map(csvField).join(','));

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, `word,phonetic,part_of_speech,meaning_zh,example_en\n${rowsOut.join('\n')}\n`, 'utf8');

const count = (field) => [...selected.values()].filter((entry) => entry[field]).length;
console.log(`written=${rowsOut.length} out=${outPath}`
  + ` phonetic=${count('phonetic')} pos=${count('partOfSpeech')} example=${count('exampleEn')}`);
