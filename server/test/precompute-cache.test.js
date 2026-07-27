const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = fs.promises;
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  analyzeTrack,
  androidVariantKey,
  appleVariantKey,
  inferAndroidSettings,
  jobForTrack,
  parseAndroidVariantKey,
  parseAppleVariantKey,
  readAndroidHeader,
  readAppleHeader,
  validAndroidVisual,
  validAppleVisual,
  validProfile,
} = require('../precompute-cache');

function writeTestWave(filePath, { sampleRate = 8_000, seconds = 0.5 } = {}) {
  const channels = 2;
  const frames = Math.floor(sampleRate * seconds);
  const dataBytes = frames * channels * 2;
  const wave = Buffer.alloc(44 + dataBytes);
  wave.write('RIFF', 0);
  wave.writeUInt32LE(36 + dataBytes, 4);
  wave.write('WAVEfmt ', 8);
  wave.writeUInt32LE(16, 16);
  wave.writeUInt16LE(1, 20);
  wave.writeUInt16LE(channels, 22);
  wave.writeUInt32LE(sampleRate, 24);
  wave.writeUInt32LE(sampleRate * channels * 2, 28);
  wave.writeUInt16LE(channels * 2, 32);
  wave.writeUInt16LE(16, 34);
  wave.write('data', 36);
  wave.writeUInt32LE(dataBytes, 40);
  for (let frame = 0; frame < frames; frame++) {
    const value = Math.round(Math.sin(2 * Math.PI * 440 * frame / sampleRate) * 0.2 * 32767);
    for (let channel = 0; channel < channels; channel++) {
      wave.writeInt16LE(value, 44 + (frame * channels + channel) * 2);
    }
  }
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, wave);
}

test('one decode creates valid profile, Android, and Apple artifacts', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-cache-test-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  const musicDir = path.join(root, 'music');
  const dataDir = path.join(root, 'data');
  const relativePath = path.join('Artist', 'Album', 'Track.wav');
  const sourcePath = path.join(musicDir, relativePath);
  writeTestWave(sourcePath);

  const options = {
    dataDir,
    platform: 'both',
    analysisSeconds: 0.25,
    visualOnly: false,
    profilesOnly: false,
    android: { fps: 20, waveformMs: 90, fftSize: 512, bars: 32, logarithmic: true },
    apple: { fps: 24, waveformMs: 80, fftSize: 1024, bars: 32, logarithmic: true },
  };
  const job = jobForTrack({ sourcePath, relativePath }, options);
  assert.ok(job);
  const written = await analyzeTrack(job, options);

  assert.deepEqual(written, { profile: true, android: true, apple: true });
  assert.equal(validProfile(job.profilePath), true);
  assert.equal(validAndroidVisual(job.androidPath), true);
  assert.equal(validAppleVisual(job.applePath), true);

  const profile = JSON.parse(await fsp.readFile(job.profilePath, 'utf8'));
  assert.ok(profile.rms > 0.13 && profile.rms < 0.15);
  assert.ok(profile.peak > 0.19 && profile.peak < 0.21);
  assert.deepEqual(
    { fps: readAndroidHeader(job.androidPath).fps, bars: readAndroidHeader(job.androidPath).bars },
    { fps: 20, bars: 32 },
  );
  const apple = readAppleHeader(job.applePath);
  assert.equal(apple.fps, 24);
  assert.equal(apple.waveformMs, 80);
  assert.equal(apple.fftSize, 1024);
  assert.equal(apple.bars, 32);

  assert.equal(jobForTrack({ sourcePath, relativePath }, options), null);
  assert.deepEqual(await inferAndroidSettings(path.join(dataDir, 'visual')), {
    count: 1,
    fps: 20,
    bars: 32,
  });
});

test('invalid cache bytes are treated as missing', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-cache-invalid-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  const androidPath = path.join(root, 'bad.fvz');
  const applePath = path.join(root, 'bad.fav');
  await fsp.writeFile(androidPath, Buffer.from('not an fvz'));
  await fsp.writeFile(applePath, Buffer.from('not a fav'));
  assert.equal(validAndroidVisual(androidPath), false);
  assert.equal(validAppleVisual(applePath), false);
});

test('Android variants use a validated settings-keyed directory', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-cache-variant-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  const settings = {
    fps: 60,
    waveformMs: 90,
    fftSize: 2048,
    bars: 64,
    logarithmic: true,
  };
  const key = 'fps60-wave90-fft2048-bars64-log1';
  assert.equal(androidVariantKey(settings), key);
  assert.deepEqual(parseAndroidVariantKey(key), settings);
  assert.equal(parseAndroidVariantKey('../visual'), null);
  assert.equal(parseAndroidVariantKey('fps60-wave90-fft1000-bars64-log1'), null);

  const relativePath = path.join('Artist', 'Track.flac');
  const job = jobForTrack(
    { sourcePath: path.join(root, 'music', relativePath), relativePath },
    {
      dataDir: path.join(root, 'data'),
      platform: 'android',
      visualOnly: true,
      profilesOnly: false,
      analysisSeconds: 10,
      force: false,
      androidVariant: true,
      android: settings,
      apple: { fps: 24, waveformMs: 80, fftSize: 1024, bars: 32, logarithmic: true },
    },
  );
  assert.ok(job?.android);
  assert.equal(
    job.androidPath,
    path.join(root, 'data', 'android-visual', key, `${relativePath}.fvz`),
  );
});

test('Apple variants use a validated settings-keyed directory', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-cache-apple-variant-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  const settings = {
    fps: 60,
    waveformMs: 80,
    fftSize: 2048,
    bars: 64,
    logarithmic: true,
  };
  const key = 'fps60-wave80-fft2048-bars64-log1';
  assert.equal(appleVariantKey(settings), key);
  assert.deepEqual(parseAppleVariantKey(key), settings);
  assert.equal(parseAppleVariantKey('../apple-visual'), null);
  assert.equal(parseAppleVariantKey('fps60-wave80-fft1-bars64-log1'), null);

  const relativePath = path.join('Artist', 'Track.flac');
  const job = jobForTrack(
    { sourcePath: path.join(root, 'music', relativePath), relativePath },
    {
      dataDir: path.join(root, 'data'),
      platform: 'apple',
      visualOnly: true,
      profilesOnly: false,
      analysisSeconds: 10,
      force: false,
      appleVariant: true,
      android: { fps: 20, waveformMs: 90, fftSize: 512, bars: 32, logarithmic: true },
      apple: settings,
    },
  );
  assert.ok(job?.apple);
  assert.equal(
    job.applePath,
    path.join(root, 'data', 'apple-visual-variant', key, `${relativePath}.fav`),
  );
});
