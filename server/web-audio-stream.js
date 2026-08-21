#!/usr/bin/env node
'use strict';

const { spawn } = require('child_process');
const { FredPlayerDsp } = require('./web-audio-dsp.js');

function option(name, fallback = '') {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && index + 1 < process.argv.length ? process.argv[index + 1] : fallback;
}

const source = option('source');
const start = Math.max(0, Number(option('start', '0')) || 0);
const format = option('format', 'flac') === 'mp3' ? 'mp3' : 'flac';
const enabled = option('leveling', '1') !== '0';
const bassGain = Math.max(0, Math.min(9, Number(option('bass-gain', '0')) || 0));
const rms = Number(option('rms', 'nan'));
const peak = Number(option('peak', 'nan'));
const profile = Number.isFinite(rms) && Number.isFinite(peak) ? { rms, peak } : null;

if (!source) {
  process.stderr.write('A source file is required.\n');
  process.exit(2);
}

const decoderArgs = ['-hide_banner', '-loglevel', 'error', '-nostdin'];
// Input seeking lets ffmpeg jump near the requested position, then discard to
// the exact timestamp while transcoding instead of decoding the whole prefix.
if (start > 0) decoderArgs.push('-ss', start.toFixed(3));
decoderArgs.push('-i', source);
decoderArgs.push(
  '-map', '0:a:0', '-vn', '-sn', '-dn',
  '-ac', '2', '-ar', '48000', '-f', 'f32le', 'pipe:1',
);

const encoderArgs = [
  '-hide_banner', '-loglevel', 'error', '-nostdin',
  '-f', 'f32le', '-ar', '48000', '-ac', '2', '-i', 'pipe:0',
  '-map_metadata', '-1',
];
if (bassGain > 0) {
  // The Node DSP feeds this encoder, so enhancement happens after FredPlayer's
  // adaptive leveling/compression. The limiter remains the final DSP stage.
  encoderArgs.push(
    '-af', `bass=g=${bassGain.toFixed(2)}:f=95:w=0.70:t=q:precision=f32,alimiter=limit=0.95:attack=5:release=100:level=false`,
  );
}
if (format === 'mp3') {
  encoderArgs.push('-c:a', 'libmp3lame', '-b:a', '320k', '-write_xing', '0', '-f', 'mp3');
} else {
  encoderArgs.push('-c:a', 'flac', '-compression_level', '2', '-f', 'flac');
}
encoderArgs.push('pipe:1');

const decoder = spawn('ffmpeg', decoderArgs, { stdio: ['ignore', 'pipe', 'pipe'] });
const encoder = spawn('ffmpeg', encoderArgs, { stdio: ['pipe', 'pipe', 'pipe'] });
const dsp = new FredPlayerDsp({ sampleRate: 48_000, profile, enabled });
let carry = Buffer.alloc(0);
let decoderFailed = false;
let encoderFailed = false;
let stopping = false;

function forwardError(prefix, chunk) {
  const message = String(chunk).trim();
  if (message) process.stderr.write(`${prefix}: ${message}\n`);
}

decoder.stderr.on('data', (chunk) => forwardError('decoder', chunk));
encoder.stderr.on('data', (chunk) => forwardError('encoder', chunk));
encoder.stdout.pipe(process.stdout);

decoder.stdout.on('data', (chunk) => {
  const joined = carry.length ? Buffer.concat([carry, chunk]) : chunk;
  const completeLength = joined.length - (joined.length % 8);
  if (completeLength > 0) {
    const complete = joined.subarray(0, completeLength);
    dsp.processBuffer(complete);
    if (!encoder.stdin.write(complete)) decoder.stdout.pause();
  }
  carry = completeLength < joined.length ? Buffer.from(joined.subarray(completeLength)) : Buffer.alloc(0);
});

encoder.stdin.on('drain', () => decoder.stdout.resume());
encoder.stdin.on('error', (error) => {
  if (error.code !== 'EPIPE') forwardError('encoder input', error.message);
});

decoder.on('error', (error) => {
  decoderFailed = true;
  forwardError('decoder launch', error.message);
  encoder.stdin.destroy();
});
decoder.on('close', (code, signal) => {
  if (code !== 0 && signal !== 'SIGTERM') decoderFailed = true;
  if (!encoder.stdin.destroyed) encoder.stdin.end();
});
encoder.on('error', (error) => {
  encoderFailed = true;
  forwardError('encoder launch', error.message);
});
encoder.on('close', (code, signal) => {
  if (code !== 0 && signal !== 'SIGTERM') encoderFailed = true;
  process.exitCode = decoderFailed || encoderFailed ? 1 : 0;
});

function stop() {
  if (stopping) return;
  stopping = true;
  decoder.stdout.destroy();
  encoder.stdin.destroy();
  encoder.stdout.unpipe(process.stdout);
  decoder.kill('SIGTERM');
  encoder.kill('SIGTERM');
  setTimeout(() => {
    decoder.kill('SIGKILL');
    encoder.kill('SIGKILL');
    process.exit(0);
  }, 750);
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
process.stdout.on('error', (error) => {
  if (error.code === 'EPIPE') stop();
});
