#!/usr/bin/env node

require('dotenv').config();

const crypto = require('crypto');
const fs = require('fs');
const fsp = fs.promises;
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const AUDIO_EXTENSIONS = new Set([
  '.mp3', '.flac', '.m4a', '.wav', '.ogg', '.aac', '.wma', '.opus', '.alac',
]);

const ANDROID_MAGIC = 0x46565a32; // FVZ2
const ANDROID_VERSION = 2;
const ANDROID_WAVEFORM_POINTS = 96;
const ANDROID_HEADER_BYTES = 24;

const APPLE_MAGIC = 0x46415631; // FAV1
const APPLE_VERSION = 1;
const APPLE_WAVEFORM_POINTS = 128;
const APPLE_HEADER_BYTES = 60;

const DEFAULT_ANDROID = Object.freeze({
  fps: 20,
  waveformMs: 90,
  fftSize: 512,
  bars: 32,
  logarithmic: true,
});
const DEFAULT_APPLE = Object.freeze({
  fps: 24,
  waveformMs: 80,
  fftSize: 1024,
  bars: 32,
  logarithmic: true,
});

// Canonical loudness-leveling settings baked into precomputed cache waveforms/
// spectra, so they reflect post-leveling audio the same way live playback does
// (NormalizingAudioPlayer.java applies leveling before feeding its visualizer
// too). There's no per-device "most common" leveling config to infer the way
// there was for Android's fps/bar count — leveling knobs are user preference,
// not something the server can observe — so this uses the app's own coded
// defaults (LevelingSettings.defaults() / PlaylistStore's output level and
// leveling strength defaults).
const LEVELING_SETTINGS = Object.freeze({
  levelAttackMs: 15,
  levelReleaseMs: 750,
  gainDownMs: 40,
  gainUpMs: 2800,
  compressorThreshold: 0.68,
  outputCeiling: 0.96,
});
const OUTPUT_LEVEL = 0.55;
const LEVELING_STRENGTH = 0.9;

/**
 * Direct port of NormalizingAudioPlayer.java's VolumeNormalizer (Android app).
 * Mirrors the adaptive loudness-matching + compressor/limiter the app applies
 * live during playback, so precomputed visualizations show the same
 * post-leveling audio a device would compute itself.
 */
class VolumeNormalizer {
  constructor(sampleRate, profile, settings) {
    this.levelAttack = VolumeNormalizer.coefficient(sampleRate, settings.levelAttackMs / 1000);
    this.levelRelease = VolumeNormalizer.coefficient(sampleRate, settings.levelReleaseMs / 1000);
    this.gainDown = VolumeNormalizer.coefficient(sampleRate, settings.gainDownMs / 1000);
    this.gainUp = VolumeNormalizer.coefficient(sampleRate, settings.gainUpMs / 1000);
    this.compressorThreshold = settings.compressorThreshold;
    this.outputCeiling = settings.outputCeiling;
    this.envelope = VolumeNormalizer.TARGET_RMS;
    this.gain = 1;
    if (profile) {
      const measured = Math.max(profile.rms, profile.peak * 0.35);
      this.envelope = clamp(measured, VolumeNormalizer.MIN_LEVEL, 0.8);
      this.gain = clamp(
        VolumeNormalizer.TARGET_RMS / Math.max(VolumeNormalizer.MIN_LEVEL, this.envelope),
        VolumeNormalizer.MIN_GAIN,
        VolumeNormalizer.MAX_GAIN,
      );
    }
  }

  static coefficient(sampleRate, seconds) {
    return 1 - Math.exp(-1 / (Math.max(1, sampleRate) * seconds));
  }

  nextGain(left, right, strength) {
    const rms = Math.sqrt((left * left + right * right) * 0.5);
    const peak = Math.max(Math.abs(left), Math.abs(right));
    const instant = Math.max(rms, peak * 0.35);
    const levelAlpha = instant > this.envelope ? this.levelAttack : this.levelRelease;
    this.envelope += (instant - this.envelope) * levelAlpha;

    let desired = VolumeNormalizer.TARGET_RMS / Math.max(VolumeNormalizer.MIN_LEVEL, this.envelope);
    desired = clamp(desired, VolumeNormalizer.MIN_GAIN, VolumeNormalizer.MAX_GAIN);
    desired = 1 + (desired - 1) * clamp(strength, 0, 1);
    const gainAlpha = desired < this.gain ? this.gainDown : this.gainUp;
    this.gain += (desired - this.gain) * gainAlpha;
    return this.gain;
  }

  protect(value) {
    const sign = Math.sign(value);
    let amount = Math.abs(value);
    if (amount > this.compressorThreshold) {
      amount = this.compressorThreshold + (amount - this.compressorThreshold) / VolumeNormalizer.COMPRESSOR_RATIO;
    }
    if (amount > this.outputCeiling) {
      amount = this.outputCeiling;
    }
    return sign * amount;
  }
}
VolumeNormalizer.TARGET_RMS = 0.18;
VolumeNormalizer.MIN_LEVEL = 0.012;
VolumeNormalizer.MAX_GAIN = 4.5;
VolumeNormalizer.MIN_GAIN = 0.2;
VolumeNormalizer.COMPRESSOR_RATIO = 6.0;

function loadExistingProfile(filePath) {
  if (!validProfile(filePath)) return null;
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    return { rms: parsed.rms, peak: parsed.peak };
  } catch (_error) {
    return null;
  }
}

function usage() {
  return `Usage: node precompute-cache.js [options]

Precomputes the server's shared leveling and visualization data. Existing valid
files are never replaced, so the command is safe to interrupt and resume.

Options:
  --music-dir PATH          Audio library (default: MUSIC_DIR from .env)
  --data-dir PATH           Cache root (default: server/data)
  --platform both|android|apple
                            Visual formats to create (default: both)
  --analysis-seconds N      Leveling scan length (default: 10)
  --visual-only             Do not create leveling profiles
  --profiles-only           Do not create visualization files
  --limit N                 Process at most N tracks that need work
  --concurrency N           Parallel decode jobs (default: min(8, cpus-1))
  --dry-run                 Report work without decoding audio
  --force                   Regenerate even tracks with already-valid caches
  --nice                    Run ffmpeg at idle I/O + low CPU priority (ionice
                            -c3 + nice -n 15), so this doesn't compete with
                            real playback/streaming reads on the same disk.
                            Recommended alongside a lower --concurrency when
                            running while the library might be in active use.
  --android-fps N           Override observed/default Android FPS
  --android-waveform-ms N   Android waveform window (default: 90)
  --android-fft-size N      Android FFT size (default: 512)
  --android-bars N          Override observed/default Android bar count
  --android-linear          Use a linear Android spectrum scale
  --apple-fps N             Apple FPS (default: 24)
  --apple-waveform-ms N     Apple waveform window (default: 80)
  --apple-fft-size N        Apple FFT size (default: 1024)
  --apple-bars N            Apple spectrum bars (default: 32)
  --apple-linear            Use a linear Apple spectrum scale
  --help                    Show this help
`;
}

function parseNumber(value, name, { integer = false, min = 0 } = {}) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || (integer && !Number.isInteger(parsed))) {
    throw new Error(`${name} must be ${integer ? 'an integer' : 'a number'} >= ${min}`);
  }
  return parsed;
}

function parseArgs(argv) {
  const options = {
    musicDir: process.env.MUSIC_DIR ? path.resolve(process.env.MUSIC_DIR) : '',
    dataDir: path.join(__dirname, 'data'),
    platform: 'both',
    analysisSeconds: 10,
    visualOnly: false,
    profilesOnly: false,
    limit: Infinity,
    concurrency: Math.max(1, Math.min(8, os.cpus().length - 1)),
    dryRun: false,
    force: false,
    nice: false,
    android: { ...DEFAULT_ANDROID },
    apple: { ...DEFAULT_APPLE },
  };

  const take = (index, option) => {
    if (index + 1 >= argv.length) {
      throw new Error(`${option} requires a value`);
    }
    return argv[index + 1];
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    switch (arg) {
      case '--help': options.help = true; break;
      case '--music-dir': options.musicDir = path.resolve(take(i++, arg)); break;
      case '--data-dir': options.dataDir = path.resolve(take(i++, arg)); break;
      case '--platform': options.platform = take(i++, arg); break;
      case '--analysis-seconds':
        options.analysisSeconds = parseNumber(take(i++, arg), arg, { min: 0 });
        break;
      case '--visual-only': options.visualOnly = true; break;
      case '--profiles-only': options.profilesOnly = true; break;
      case '--limit': options.limit = parseNumber(take(i++, arg), arg, { integer: true, min: 1 }); break;
      case '--concurrency': options.concurrency = parseNumber(take(i++, arg), arg, { integer: true, min: 1 }); break;
      case '--dry-run': options.dryRun = true; break;
      case '--force': options.force = true; break;
      case '--nice': options.nice = true; break;
      case '--android-fps': options.android.fps = parseNumber(take(i++, arg), arg, { integer: true, min: 1 }); break;
      case '--android-waveform-ms': options.android.waveformMs = parseNumber(take(i++, arg), arg, { integer: true, min: 1 }); break;
      case '--android-fft-size': options.android.fftSize = parseNumber(take(i++, arg), arg, { integer: true, min: 2 }); break;
      case '--android-bars': options.android.bars = parseNumber(take(i++, arg), arg, { integer: true, min: 1 }); break;
      case '--android-linear': options.android.logarithmic = false; break;
      case '--apple-fps': options.apple.fps = parseNumber(take(i++, arg), arg, { min: 1 }); break;
      case '--apple-waveform-ms': options.apple.waveformMs = parseNumber(take(i++, arg), arg, { min: 1 }); break;
      case '--apple-fft-size': options.apple.fftSize = parseNumber(take(i++, arg), arg, { integer: true, min: 2 }); break;
      case '--apple-bars': options.apple.bars = parseNumber(take(i++, arg), arg, { integer: true, min: 1 }); break;
      case '--apple-linear': options.apple.logarithmic = false; break;
      default: throw new Error(`Unknown option: ${arg}`);
    }
  }

  if (!['both', 'android', 'apple'].includes(options.platform)) {
    throw new Error('--platform must be both, android, or apple');
  }
  if (options.visualOnly && options.profilesOnly) {
    throw new Error('--visual-only and --profiles-only cannot be combined');
  }
  for (const [label, size] of [
    ['--android-fft-size', options.android.fftSize],
    ['--apple-fft-size', options.apple.fftSize],
  ]) {
    if ((size & (size - 1)) !== 0) {
      throw new Error(`${label} must be a power of two`);
    }
  }
  return options;
}

async function walkAudioFiles(directory, baseDirectory, output = []) {
  const entries = await fsp.readdir(directory, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await walkAudioFiles(fullPath, baseDirectory, output);
    } else if (entry.isFile() && AUDIO_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
      output.push({
        sourcePath: fullPath,
        relativePath: path.relative(baseDirectory, fullPath),
      });
    }
  }
  return output;
}

async function walkFiles(directory, output = []) {
  let entries;
  try {
    entries = await fsp.readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return output;
    throw error;
  }
  for (const entry of entries) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) await walkFiles(fullPath, output);
    else if (entry.isFile()) output.push(fullPath);
  }
  return output;
}

function validProfile(filePath) {
  try {
    const profile = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    return Number.isFinite(profile.rms)
      && profile.rms >= 0
      && Number.isFinite(profile.peak)
      && profile.peak >= 0;
  } catch (_error) {
    return false;
  }
}

function readAndroidHeader(filePath) {
  try {
    const stats = fs.statSync(filePath);
    if (!stats.isFile() || stats.size < ANDROID_HEADER_BYTES) return null;
    const descriptor = fs.openSync(filePath, 'r');
    const header = Buffer.alloc(ANDROID_HEADER_BYTES);
    try {
      if (fs.readSync(descriptor, header, 0, header.length, 0) !== header.length) return null;
    } finally {
      fs.closeSync(descriptor);
    }
    const result = {
      magic: header.readUInt32BE(0),
      version: header.readUInt32BE(4),
      fps: header.readUInt32BE(8),
      waveformPoints: header.readUInt32BE(12),
      bars: header.readUInt32BE(16),
      frameCount: header.readUInt32BE(20),
    };
    const expected = ANDROID_HEADER_BYTES
      + result.frameCount * (result.waveformPoints + result.bars);
    if (result.magic !== ANDROID_MAGIC
        || result.version !== ANDROID_VERSION
        || result.fps < 1
        || result.waveformPoints !== ANDROID_WAVEFORM_POINTS
        || result.bars < 1
        || result.frameCount < 1
        || expected !== stats.size) {
      return null;
    }
    return result;
  } catch (_error) {
    return null;
  }
}

function validAndroidVisual(filePath) {
  return readAndroidHeader(filePath) !== null;
}

function readAppleHeader(filePath) {
  try {
    const stats = fs.statSync(filePath);
    if (!stats.isFile() || stats.size < APPLE_HEADER_BYTES) return null;
    const descriptor = fs.openSync(filePath, 'r');
    const header = Buffer.alloc(APPLE_HEADER_BYTES);
    try {
      if (fs.readSync(descriptor, header, 0, header.length, 0) !== header.length) return null;
    } finally {
      fs.closeSync(descriptor);
    }
    const result = {
      magic: header.readUInt32BE(0),
      version: header.readUInt32BE(4),
      fps: header.readDoubleBE(8),
      waveformMs: header.readDoubleBE(16),
      fftSize: header.readUInt32BE(24),
      waveformPoints: header.readUInt32BE(28),
      bars: header.readUInt32BE(32),
      flags: header.readUInt32BE(36),
      frameCount: header.readUInt32BE(40),
      frameInterval: header.readDoubleBE(44),
      created: header.readDoubleBE(52),
    };
    const expected = APPLE_HEADER_BYTES
      + result.frameCount * (result.waveformPoints + result.bars);
    if (result.magic !== APPLE_MAGIC
        || result.version !== APPLE_VERSION
        || !Number.isFinite(result.fps)
        || result.fps < 1
        || !Number.isFinite(result.waveformMs)
        || result.waveformMs <= 0
        || result.fftSize < 2
        || result.waveformPoints !== APPLE_WAVEFORM_POINTS
        || result.bars < 1
        || result.frameCount < 1
        || !Number.isFinite(result.frameInterval)
        || result.frameInterval <= 0
        || expected !== stats.size) {
      return null;
    }
    return result;
  } catch (_error) {
    return null;
  }
}

function validAppleVisual(filePath) {
  return readAppleHeader(filePath) !== null;
}

async function inferAndroidSettings(visualDirectory) {
  const counts = new Map();
  for (const filePath of await walkFiles(visualDirectory)) {
    if (!filePath.endsWith('.fvz')) continue;
    const header = readAndroidHeader(filePath);
    if (!header) continue;
    const key = `${header.fps}|${header.bars}`;
    const value = counts.get(key) || { count: 0, fps: header.fps, bars: header.bars };
    value.count++;
    counts.set(key, value);
  }
  const observed = [...counts.values()].sort((left, right) => right.count - left.count)[0];
  return observed || null;
}

function temporaryPath(targetPath) {
  return `${targetPath}.tmp-${process.pid}-${crypto.randomBytes(6).toString('hex')}`;
}

class BufferedFile {
  constructor(targetPath, headerBytes) {
    this.targetPath = targetPath;
    this.tempPath = temporaryPath(targetPath);
    fs.mkdirSync(path.dirname(targetPath), { recursive: true });
    this.descriptor = fs.openSync(this.tempPath, 'wx');
    fs.writeSync(this.descriptor, Buffer.alloc(headerBytes));
    this.parts = [];
    this.bufferedBytes = 0;
    this.closed = false;
  }

  append(buffer) {
    this.parts.push(buffer);
    this.bufferedBytes += buffer.length;
    if (this.bufferedBytes >= 1024 * 1024) this.flush();
  }

  flush() {
    if (this.bufferedBytes === 0) return;
    fs.writeSync(this.descriptor, Buffer.concat(this.parts, this.bufferedBytes));
    this.parts = [];
    this.bufferedBytes = 0;
  }

  finish(header, validator, { force = false } = {}) {
    this.flush();
    fs.writeSync(this.descriptor, header, 0, header.length, 0);
    fs.fsyncSync(this.descriptor);
    fs.closeSync(this.descriptor);
    this.closed = true;
    if (!force && validator(this.targetPath)) {
      fs.unlinkSync(this.tempPath);
      return false;
    }
    fs.renameSync(this.tempPath, this.targetPath);
    return true;
  }

  discard() {
    if (!this.closed) {
      try { fs.closeSync(this.descriptor); } catch (_error) {}
      this.closed = true;
    }
    try { fs.unlinkSync(this.tempPath); } catch (_error) {}
  }
}

class FFT {
  constructor(size) {
    this.size = size;
    this.real = new Float64Array(size);
    this.imaginary = new Float64Array(size);
    this.cosine = new Float64Array(size / 2);
    this.sine = new Float64Array(size / 2);
    for (let index = 0; index < size / 2; index++) {
      const angle = -2 * Math.PI * index / size;
      this.cosine[index] = Math.cos(angle);
      this.sine[index] = Math.sin(angle);
    }
  }

  transform(samples, window) {
    const { size, real, imaginary } = this;
    for (let index = 0; index < size; index++) {
      real[index] = samples[index] * window[index];
      imaginary[index] = 0;
    }
    for (let index = 1, reversed = 0; index < size; index++) {
      let bit = size >> 1;
      while (reversed & bit) {
        reversed ^= bit;
        bit >>= 1;
      }
      reversed ^= bit;
      if (index < reversed) {
        [real[index], real[reversed]] = [real[reversed], real[index]];
        [imaginary[index], imaginary[reversed]] = [imaginary[reversed], imaginary[index]];
      }
    }
    for (let length = 2; length <= size; length <<= 1) {
      const half = length >> 1;
      const twiddleStep = size / length;
      for (let start = 0; start < size; start += length) {
        for (let offset = 0; offset < half; offset++) {
          const twiddle = offset * twiddleStep;
          const right = start + offset + half;
          const left = start + offset;
          const realPart = real[right] * this.cosine[twiddle]
            - imaginary[right] * this.sine[twiddle];
          const imaginaryPart = real[right] * this.sine[twiddle]
            + imaginary[right] * this.cosine[twiddle];
          real[right] = real[left] - realPart;
          imaginary[right] = imaginary[left] - imaginaryPart;
          real[left] += realPart;
          imaginary[left] += imaginaryPart;
        }
      }
    }
    return this;
  }

  magnitude(bin) {
    return Math.hypot(this.real[bin], this.imaginary[bin]);
  }
}

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

class AndroidVisualWriter {
  constructor(targetPath, sampleRate, settings, force = false) {
    this.output = new BufferedFile(targetPath, ANDROID_HEADER_BYTES);
    this.sampleRate = sampleRate;
    this.settings = settings;
    this.force = force;
    this.frameCount = 0;
    this.framesPerEmit = Math.max(1, Math.floor(sampleRate / Math.max(1, settings.fps)));
    this.framesSinceEmit = 0;
    this.waveform = new Float32Array(Math.max(
      ANDROID_WAVEFORM_POINTS,
      Math.floor(sampleRate * settings.waveformMs / 1000),
    ));
    this.waveformWrite = 0;
    this.waveformFilled = 0;
    this.fftRing = new Float32Array(settings.fftSize);
    this.fftOrdered = new Float32Array(settings.fftSize);
    this.fftWrite = 0;
    this.fftFilled = 0;
    this.window = new Float64Array(settings.fftSize);
    for (let index = 0; index < settings.fftSize; index++) {
      this.window[index] = 0.5 - 0.5 * Math.cos(
        2 * Math.PI * index / Math.max(1, settings.fftSize - 1),
      );
    }
    this.fft = new FFT(settings.fftSize);
  }

  accept(sample) {
    const clipped = clamp(sample, -1, 1);
    this.waveform[this.waveformWrite] = clipped;
    this.waveformWrite = (this.waveformWrite + 1) % this.waveform.length;
    this.waveformFilled = Math.min(this.waveformFilled + 1, this.waveform.length);
    this.fftRing[this.fftWrite] = clipped;
    this.fftWrite = (this.fftWrite + 1) % this.fftRing.length;
    this.fftFilled = Math.min(this.fftFilled + 1, this.fftRing.length);
    this.framesSinceEmit++;
    if (this.framesSinceEmit < this.framesPerEmit || this.fftFilled < this.fftRing.length) return;
    this.framesSinceEmit = 0;
    this.writeFrame();
  }

  writeFrame() {
    const frame = Buffer.alloc(ANDROID_WAVEFORM_POINTS + this.settings.bars);
    const start = (this.waveformWrite - this.waveformFilled + this.waveform.length)
      % this.waveform.length;
    for (let point = 0; point < ANDROID_WAVEFORM_POINTS; point++) {
      const offset = point * (this.waveformFilled - 1) / (ANDROID_WAVEFORM_POINTS - 1);
      const sample = this.waveform[(start + Math.floor(offset)) % this.waveform.length];
      frame[point] = Math.round(clamp(sample, -1, 1) * 127) & 0xff;
    }

    for (let index = 0; index < this.fftRing.length; index++) {
      this.fftOrdered[index] = this.fftRing[(this.fftWrite + index) % this.fftRing.length];
    }
    this.fft.transform(this.fftOrdered, this.window);
    const maxBin = Math.max(2, Math.floor(this.settings.fftSize / 2) - 1);
    const minBin = Math.max(1, 40 * this.settings.fftSize / this.sampleRate);
    for (let bar = 0; bar < this.settings.bars; bar++) {
      let bin;
      if (this.settings.logarithmic) {
        const ratio = bar / Math.max(1, this.settings.bars - 1);
        bin = Math.round(Math.exp(
          Math.log(minBin) + (Math.log(maxBin) - Math.log(minBin)) * ratio,
        ));
      } else {
        bin = 1 + Math.round(
          (maxBin - 1) * bar / Math.max(1, this.settings.bars - 1),
        );
      }
      bin = clamp(bin, 1, maxBin);
      const magnitude = this.fft.magnitude(bin) / Math.max(1, this.settings.fftSize * 0.5);
      const normalized = this.settings.logarithmic
        ? Math.log10(1 + magnitude * 85)
        : magnitude * 12;
      frame[ANDROID_WAVEFORM_POINTS + bar] = Math.round(clamp(normalized, 0, 1) * 100);
    }
    this.output.append(frame);
    this.frameCount++;
  }

  finish() {
    if (this.frameCount < 1) throw new Error('audio was too short for an Android visual frame');
    const header = Buffer.alloc(ANDROID_HEADER_BYTES);
    header.writeUInt32BE(ANDROID_MAGIC, 0);
    header.writeUInt32BE(ANDROID_VERSION, 4);
    header.writeUInt32BE(this.settings.fps, 8);
    header.writeUInt32BE(ANDROID_WAVEFORM_POINTS, 12);
    header.writeUInt32BE(this.settings.bars, 16);
    header.writeUInt32BE(this.frameCount, 20);
    return this.output.finish(header, validAndroidVisual, { force: this.force });
  }

  discard() { this.output.discard(); }
}

class AppleVisualWriter {
  constructor(targetPath, sampleRate, settings, force = false) {
    this.output = new BufferedFile(targetPath, APPLE_HEADER_BYTES);
    this.sampleRate = sampleRate;
    this.settings = settings;
    this.force = force;
    this.frameSize = Math.max(settings.fftSize, Math.floor(sampleRate / Math.max(1, settings.fps)));
    this.pending = new Float32Array(this.frameSize);
    this.pendingCount = 0;
    this.frameCount = 0;
    this.fftSamples = new Float32Array(settings.fftSize);
    this.window = new Float64Array(settings.fftSize);
    for (let index = 0; index < settings.fftSize; index++) {
      this.window[index] = 0.5 - 0.5 * Math.cos(2 * Math.PI * index / settings.fftSize);
    }
    this.fft = new FFT(settings.fftSize);
  }

  accept(sample) {
    this.pending[this.pendingCount++] = clamp(sample, -1, 1);
    if (this.pendingCount === this.frameSize) this.writeFrame();
  }

  writeFrame() {
    const frame = Buffer.alloc(APPLE_WAVEFORM_POINTS + this.settings.bars);
    const waveCount = Math.min(
      this.pendingCount,
      Math.max(1, Math.floor(this.sampleRate * this.settings.waveformMs / 1000)),
    );
    const waveStart = this.pendingCount - waveCount;
    const stride = Math.max(1, Math.floor(waveCount / APPLE_WAVEFORM_POINTS));
    for (let point = 0; point < APPLE_WAVEFORM_POINTS; point++) {
      const start = Math.min(point * stride, waveCount - 1);
      const end = Math.min(start + stride, waveCount);
      let sum = 0;
      for (let index = start; index < end; index++) sum += this.pending[waveStart + index];
      frame[point] = Math.round(clamp(sum / Math.max(1, end - start), -1, 1) * 127) & 0xff;
    }

    if (this.pendingCount >= this.settings.fftSize) {
      this.fftSamples.set(this.pending.subarray(0, this.settings.fftSize));
      this.fft.transform(this.fftSamples, this.window);
      const half = Math.floor(this.settings.fftSize / 2);
      for (let bar = 0; bar < this.settings.bars; bar++) {
        let lower;
        let upper;
        if (this.settings.logarithmic) {
          lower = Math.floor(Math.pow(half, bar / this.settings.bars));
          upper = Math.max(lower + 1, Math.floor(Math.pow(half, (bar + 1) / this.settings.bars)));
        } else {
          lower = Math.floor(bar * half / this.settings.bars);
          upper = Math.max(lower + 1, Math.floor((bar + 1) * half / this.settings.bars));
        }
        lower = Math.max(1, lower);
        upper = Math.min(half, Math.max(lower + 1, upper));
        let magnitude = 0;
        for (let bin = lower; bin < upper; bin++) {
          magnitude = Math.max(magnitude, this.fft.magnitude(bin) / this.settings.fftSize);
        }
        const normalized = (20 * Math.log10(Math.max(magnitude, 0.000001)) + 72) / 72;
        frame[APPLE_WAVEFORM_POINTS + bar] = Math.round(clamp(normalized, 0, 1) * 255);
      }
    }
    this.output.append(frame);
    this.frameCount++;
    this.pendingCount = 0;
  }

  finish() {
    if (this.pendingCount > 0) this.writeFrame();
    if (this.frameCount < 1) throw new Error('audio contained no Apple visual frames');
    const header = Buffer.alloc(APPLE_HEADER_BYTES);
    header.writeUInt32BE(APPLE_MAGIC, 0);
    header.writeUInt32BE(APPLE_VERSION, 4);
    header.writeDoubleBE(this.settings.fps, 8);
    header.writeDoubleBE(this.settings.waveformMs, 16);
    header.writeUInt32BE(this.settings.fftSize, 24);
    header.writeUInt32BE(APPLE_WAVEFORM_POINTS, 28);
    header.writeUInt32BE(this.settings.bars, 32);
    header.writeUInt32BE(this.settings.logarithmic ? 1 : 0, 36);
    header.writeUInt32BE(this.frameCount, 40);
    header.writeDoubleBE(this.frameSize / this.sampleRate, 44);
    header.writeDoubleBE(Date.now() / 1000, 52);
    return this.output.finish(header, validAppleVisual, { force: this.force });
  }

  discard() { this.output.discard(); }
}

function commandOutput(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    const stdout = [];
    const stderr = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    child.stderr.on('data', (chunk) => stderr.push(chunk));
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) resolve(Buffer.concat(stdout).toString('utf8'));
      else reject(new Error(`${command} exited ${code}: ${Buffer.concat(stderr).toString('utf8').trim()}`));
    });
  });
}

async function audioFormat(sourcePath) {
  const raw = await commandOutput('ffprobe', [
    '-v', 'error', '-select_streams', 'a:0',
    '-show_entries', 'stream=sample_rate,channels', '-of', 'json', sourcePath,
  ]);
  const stream = JSON.parse(raw).streams?.[0];
  const sampleRate = Number(stream?.sample_rate);
  const channels = Number(stream?.channels);
  if (!Number.isInteger(sampleRate) || sampleRate < 1
      || !Number.isInteger(channels) || channels < 1) {
    throw new Error('ffprobe did not report a usable audio stream');
  }
  return { sampleRate, channels };
}

async function atomicProfileWrite(filePath, profile, { force = false } = {}) {
  if (!force && validProfile(filePath)) return false;
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  const tempPath = temporaryPath(filePath);
  try {
    await fsp.writeFile(tempPath, JSON.stringify(profile), { flag: 'wx' });
    if (!force && validProfile(filePath)) {
      await fsp.unlink(tempPath);
      return false;
    }
    await fsp.rename(tempPath, filePath);
    return true;
  } catch (error) {
    try { await fsp.unlink(tempPath); } catch (_unlinkError) {}
    throw error;
  }
}

async function analyzeTrack(job, options, abortSignal) {
  const { sampleRate, channels } = await audioFormat(job.sourcePath);
  const android = job.android
    ? new AndroidVisualWriter(job.androidPath, sampleRate, options.android, options.force)
    : null;
  const apple = job.apple
    ? new AppleVisualWriter(job.applePath, sampleRate, options.apple, options.force)
    : null;
  const normalizer = (android || apple)
    ? new VolumeNormalizer(sampleRate, loadExistingProfile(job.profilePath), LEVELING_SETTINGS)
    : null;
  const profileFrameLimit = Math.floor(options.analysisSeconds * sampleRate);
  let profileFrames = 0;
  let profileSamples = 0;
  let sumSquares = 0;
  let peak = 0;
  let pending = Buffer.alloc(0);
  let child;

  try {
    const args = ['-v', 'error', '-nostdin', '-threads', '1', '-i', job.sourcePath,
      '-map', '0:a:0', '-vn', '-sn', '-dn'];
    if (!android && !apple) args.push('-t', String(options.analysisSeconds));
    args.push('-f', 'f32le', '-acodec', 'pcm_f32le', 'pipe:1');
    // options.nice: background-safe mode, used by the server's own in-process
    // auto-trigger (and available to the CLI via --nice) so this doesn't
    // compete with real playback/streaming reads on the same disk. CPU
    // `nice` alone isn't enough — decoding audio is I/O-bound, not CPU-bound,
    // so the actual contention is disk read scheduling, not CPU time. `ionice
    // -c3` (idle class) tells the kernel's I/O scheduler to only service
    // these reads when nothing else wants the disk, which is what actually
    // matters here; `nice -n 15` on top keeps CPU scheduling out of the way
    // too. (Learned this the hard way: an earlier `nice`-only run with 8
    // concurrent decodes against the same drive the desktop app streams from
    // caused real playback to stall/hang.)
    child = options.nice
      ? spawn('ionice', ['-c3', 'nice', '-n', '15', 'ffmpeg', ...args], { stdio: ['ignore', 'pipe', 'pipe'] })
      : spawn('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stderr = '';
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => { stderr = (stderr + chunk).slice(-8192); });
    const closePromise = new Promise((resolve, reject) => {
      child.on('error', reject);
      child.on('close', (code, signal) => resolve({ code, signal }));
    });
    const abort = () => child.kill('SIGTERM');
    abortSignal?.addEventListener('abort', abort, { once: true });
    const bytesPerFrame = channels * 4;

    for await (const chunk of child.stdout) {
      if (abortSignal?.aborted) break;
      const data = pending.length ? Buffer.concat([pending, chunk]) : chunk;
      const usable = data.length - (data.length % bytesPerFrame);
      for (let offset = 0; offset < usable; offset += bytesPerFrame) {
        let left = 0;
        let right = 0;
        let leftCount = 0;
        let rightCount = 0;
        let channel0Sample = 0;
        const collectProfile = job.profile && profileFrames < profileFrameLimit;
        for (let channel = 0; channel < channels; channel++) {
          const sample = clamp(data.readFloatLE(offset + channel * 4), -1, 1);
          if (channel === 0) channel0Sample = sample;
          if (collectProfile) {
            const amount = Math.abs(sample);
            peak = Math.max(peak, amount);
            if (amount >= 0.004) {
              sumSquares += sample * sample;
              profileSamples++;
            }
          }
          if (channels === 1) {
            left += sample;
            right += sample;
            leftCount++;
            rightCount++;
          } else if (channel % 2 === 0) {
            left += sample;
            leftCount++;
          } else {
            right += sample;
            rightCount++;
          }
        }
        if (collectProfile) profileFrames++;

        // Feed the visualizers post-leveling audio (gain-adjusted, compressed/
        // limited), matching what the Android app itself now does in its own
        // local cache-writing path — not the raw pre-leveling decode. Profile
        // measurement above stays on the raw samples; that's the track's
        // inherent loudness, which is what the normalizer needs to measure.
        if (normalizer) {
          left = leftCount ? left / leftCount : 0;
          right = rightCount ? right / rightCount : left;
          const gain = normalizer.nextGain(left, right, LEVELING_STRENGTH);
          // protect() is a nonlinear compressor/limiter, so (as in the Java
          // original) each channel is protected independently before
          // averaging — protect(avg) is not the same as avg(protect(...)).
          const leveledLeft = normalizer.protect(left * gain * OUTPUT_LEVEL);
          const leveledRight = normalizer.protect(right * gain * OUTPUT_LEVEL);
          if (apple) {
            apple.accept(normalizer.protect(channel0Sample * gain * OUTPUT_LEVEL));
          }
          if (android) {
            android.accept((leveledLeft + leveledRight) * 0.5);
          }
        }
      }
      pending = Buffer.from(data.subarray(usable));
    }
    const result = await closePromise;
    abortSignal?.removeEventListener('abort', abort);
    if (abortSignal?.aborted) throw new Error('interrupted');
    if (result.code !== 0) {
      throw new Error(`ffmpeg exited ${result.code ?? result.signal}: ${stderr.trim()}`);
    }

    const written = { profile: false, android: false, apple: false };
    if (android) written.android = android.finish();
    if (apple) written.apple = apple.finish();
    if (job.profile) {
      written.profile = await atomicProfileWrite(job.profilePath, {
        rms: profileSamples ? Math.sqrt(sumSquares / profileSamples) : 0.18,
        peak,
      }, { force: options.force });
    }
    return written;
  } catch (error) {
    if (child && child.exitCode === null) child.kill('SIGTERM');
    android?.discard();
    apple?.discard();
    throw error;
  }
}

function jobForTrack(track, options) {
  const profilePath = path.join(options.dataDir, 'profiles', `${track.relativePath}.json`);
  const androidPath = path.join(options.dataDir, 'visual', `${track.relativePath}.fvz`);
  const applePath = path.join(options.dataDir, 'apple-visual', `${track.relativePath}.fav`);
  const visualEnabled = !options.profilesOnly;
  const job = {
    ...track,
    profilePath,
    androidPath,
    applePath,
    profile: !options.visualOnly && options.analysisSeconds > 0 && (options.force || !validProfile(profilePath)),
    android: visualEnabled
      && (options.platform === 'both' || options.platform === 'android')
      && (options.force || !validAndroidVisual(androidPath)),
    apple: visualEnabled
      && (options.platform === 'both' || options.platform === 'apple')
      && (options.force || !validAppleVisual(applePath)),
  };
  return job.profile || job.android || job.apple ? job : null;
}

function formatDuration(seconds) {
  if (!Number.isFinite(seconds)) return 'unknown';
  const rounded = Math.max(0, Math.round(seconds));
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const remainder = rounded % 60;
  return hours ? `${hours}h ${minutes}m` : `${minutes}m ${remainder}s`;
}

async function main(argv = process.argv.slice(2)) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    console.error(`Error: ${error.message}\n\n${usage()}`);
    process.exitCode = 2;
    return;
  }
  if (options.help) {
    console.log(usage());
    return;
  }
  if (!options.musicDir) {
    console.error('Error: MUSIC_DIR is not set; use --music-dir or configure server/.env');
    process.exitCode = 2;
    return;
  }

  const inferred = await inferAndroidSettings(path.join(options.dataDir, 'visual'));
  const fpsWasExplicit = argv.includes('--android-fps');
  const barsWereExplicit = argv.includes('--android-bars');
  if (inferred) {
    if (!fpsWasExplicit) options.android.fps = inferred.fps;
    if (!barsWereExplicit) options.android.bars = inferred.bars;
  }

  const tracks = await walkAudioFiles(options.musicDir, options.musicDir);
  tracks.sort((left, right) => left.relativePath.localeCompare(right.relativePath));
  const allJobs = tracks.map((track) => jobForTrack(track, options)).filter(Boolean);
  const jobs = allJobs.slice(0, options.limit);
  console.log(`Library: ${tracks.length} tracks; ${allJobs.length} need work; ${jobs.length} selected`);
  if (!options.visualOnly && options.analysisSeconds > 0) {
    const missing = allJobs.filter((job) => job.profile).length;
    console.log(`Profiles: ${tracks.length - missing} valid; ${missing} missing`);
  }
  if (!options.profilesOnly) {
    if (options.platform === 'both' || options.platform === 'android') {
      const missing = allJobs.filter((job) => job.android).length;
      console.log(`Android visuals: ${tracks.length - missing} valid; ${missing} missing`);
      console.log(`  settings: ${options.android.fps} FPS, ${options.android.waveformMs} ms, FFT ${options.android.fftSize}, ${options.android.bars} bars, ${options.android.logarithmic ? 'log' : 'linear'}`);
    }
    if (options.platform === 'both' || options.platform === 'apple') {
      const missing = allJobs.filter((job) => job.apple).length;
      console.log(`Apple visuals: ${tracks.length - missing} valid; ${missing} missing`);
      console.log(`  settings: ${options.apple.fps} FPS, ${options.apple.waveformMs} ms, FFT ${options.apple.fftSize}, ${options.apple.bars} bars, ${options.apple.logarithmic ? 'log' : 'linear'}`);
    }
    if (inferred) console.log(`Android FPS/bars inferred from ${inferred.count} existing valid server files`);
  }
  if (!options.visualOnly) console.log(`Leveling: first ${options.analysisSeconds} seconds, silence-gated RMS and peak`);
  if (options.dryRun || jobs.length === 0) return;

  const controller = new AbortController();
  const interrupt = () => controller.abort();
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  const { failed } = await runJobs(jobs, options, { signal: controller.signal });
  process.removeListener('SIGINT', interrupt);
  process.removeListener('SIGTERM', interrupt);
  if (failed) process.exitCode = 1;
}

/**
 * Runs a list of jobs (from jobForTrack) through a bounded concurrent worker pool,
 * logging progress the same way whether invoked from the CLI (main()) or the
 * server's own in-process auto-precompute trigger. Shared so both paths use
 * identical job-running behavior — skip-if-valid checks, atomic writes, per-job
 * error handling — rather than maintaining two copies of this loop.
 */
async function runJobs(jobs, options, { signal } = {}) {
  const ownController = signal ? null : new AbortController();
  const effectiveSignal = signal || ownController.signal;
  const started = Date.now();
  let completed = 0;
  let failed = 0;
  let nextIndex = 0;

  function logProgress() {
    const elapsed = (Date.now() - started) / 1000;
    const attempted = completed + failed;
    const remaining = attempted ? elapsed / attempted * (jobs.length - attempted) : Infinity;
    console.log(`  progress: ${attempted}/${jobs.length}; elapsed ${formatDuration(elapsed)}; ETA ${formatDuration(remaining)}`);
  }

  async function worker() {
    while (!effectiveSignal.aborted) {
      const index = nextIndex++;
      if (index >= jobs.length) return;
      const job = jobs[index];
      const relative = job.relativePath.split(path.sep).join('/');
      const kinds = [job.profile && 'profile', job.android && 'Android', job.apple && 'Apple'].filter(Boolean).join('+');
      console.log(`[${index + 1}/${jobs.length}] ${kinds}: ${relative}`);
      try {
        await analyzeTrack(job, options, effectiveSignal);
        completed++;
      } catch (error) {
        if (effectiveSignal.aborted) return;
        failed++;
        console.error(`  failed (${relative}): ${error.message}`);
      }
      logProgress();
    }
  }

  const workerCount = Math.max(1, Math.min(options.concurrency, jobs.length));
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  console.log(`Finished: ${completed} completed, ${failed} failed${effectiveSignal.aborted ? ', interrupted safely' : ''}`);
  return { completed, failed, aborted: effectiveSignal.aborted };
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`Fatal: ${error.stack || error.message}`);
    process.exitCode = 1;
  });
}

module.exports = {
  ANDROID_MAGIC,
  ANDROID_VERSION,
  APPLE_MAGIC,
  APPLE_VERSION,
  DEFAULT_ANDROID,
  DEFAULT_APPLE,
  analyzeTrack,
  inferAndroidSettings,
  jobForTrack,
  parseArgs,
  runJobs,
  readAndroidHeader,
  readAppleHeader,
  validAndroidVisual,
  validAppleVisual,
  validProfile,
  walkAudioFiles,
};
