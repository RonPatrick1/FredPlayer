function parseTimestamp(text) {
  const match = /(\d{2}):(\d{2}):(\d{2}(?:\.\d+)?)/.exec(text);
  if (!match) return null;
  const [, hh, mm, ss] = match;
  return Number(hh) * 3600 + Number(mm) * 60 + Number(ss);
}

const RANGE_PATTERN = /^(\d{2}:\d{2}:\d{2}\.\d+)\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d+)$/;
const WORD_PATTERN = /<(\d{2}:\d{2}:\d{2}\.\d+)>([^<]*)/g;
const SECTION_PATTERN = /^\[(.+)\]$/;
const VERSION_PATTERN = /^Lyrics-Sidecar-Version:\s*(.+)$/;
const LANGUAGE_PATTERN = /^Language:\s*(.+)$/;

// Parses one section's phrase blocks starting at lines[0], stopping at the
// next [Section] header or end of input. Each block is a range line
// ("start --> end") followed by a line of inline-timestamped words
// ("<start> word<start> word..."). Returns the phrases plus how many lines
// were consumed, so the caller can resume scanning after this section.
function parsePhrases(lines) {
  const phrases = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i].trim();
    if (!line) {
      i += 1;
      continue;
    }
    if (SECTION_PATTERN.test(line)) break;
    const range = RANGE_PATTERN.exec(line);
    if (!range) {
      i += 1;
      continue;
    }
    const start = parseTimestamp(range[1]);
    const end = parseTimestamp(range[2]);
    i += 1;
    const wordLine = i < lines.length ? lines[i] : '';
    i += 1;
    const words = [];
    WORD_PATTERN.lastIndex = 0;
    let match;
    while ((match = WORD_PATTERN.exec(wordLine)) !== null) {
      const time = parseTimestamp(match[1]);
      const text = match[2].trim();
      if (text) words.push({ time, text });
    }
    if (words.length) phrases.push({ start, end, text: words.map((w) => w.text).join(' '), words });
  }
  return { phrases, consumed: i };
}

// Parses a full .lyrics.txt sidecar: a small header (version/language),
// then one or more [Section Name] blocks (e.g. "[Original]", optionally
// "[English Translation]" for non-English tracks) each holding phrase
// blocks in the format produced by the Whisper-based transcription pass.
function parseLyricsSidecar(raw) {
  const lines = String(raw).split(/\r?\n/);
  let version = null;
  let language = null;
  let i = 0;
  for (; i < lines.length; i += 1) {
    const line = lines[i];
    if (!line.trim()) {
      i += 1;
      break;
    }
    const versionMatch = VERSION_PATTERN.exec(line);
    if (versionMatch) version = versionMatch[1].trim();
    const languageMatch = LANGUAGE_PATTERN.exec(line);
    if (languageMatch) language = languageMatch[1].trim();
  }
  const sections = {};
  while (i < lines.length) {
    const line = lines[i].trim();
    if (!line) {
      i += 1;
      continue;
    }
    const sectionMatch = SECTION_PATTERN.exec(line);
    if (!sectionMatch) {
      i += 1;
      continue;
    }
    i += 1;
    const { phrases, consumed } = parsePhrases(lines.slice(i));
    sections[sectionMatch[1]] = phrases;
    i += consumed;
  }
  return { version, language, sections };
}

module.exports = { parseLyricsSidecar };
