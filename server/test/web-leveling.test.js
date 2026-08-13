const assert = require('node:assert/strict');
const test = require('node:test');

const { DEFAULTS, gainForProfile } = require('../web-leveling');
const { FredPlayerDsp } = require('../web-audio-dsp');

test('web leveling defaults match the last recorded phone setup', () => {
  assert.equal(DEFAULTS.outputLevel, 1);
  assert.equal(DEFAULTS.levelingStrength, 1);
  assert.equal(DEFAULTS.analysisSeconds, 25);
  assert.equal(DEFAULTS.compressorThreshold, 0.43);
  assert.equal(DEFAULTS.outputCeiling, 0.96);
});

test('quiet web tracks retain unity gain', () => {
  assert.equal(gainForProfile({ rms: 0.05, peak: 0.5 }), 1);
});

test('loud web tracks receive server-calculated reduction', () => {
  const gain = gainForProfile({ rms: 0.5, peak: 1 });
  assert.ok(gain > 0.35 && gain < 0.5);
});

test('web leveling respects the output ceiling and rejects invalid profiles', () => {
  assert.ok(gainForProfile({ rms: 0.05, peak: 2 }) < 0.5);
  assert.equal(gainForProfile({ rms: null, peak: 1 }), 1);
  assert.equal(gainForProfile(null), 1);
});

test('server PCM DSP follows the native FredPlayer sample algorithm', () => {
  const profile = { rms: 0.08, peak: 0.4 };
  const dsp = new FredPlayerDsp({ sampleRate: 48_000, profile });
  let envelope = Math.max(DEFAULTS.minimumLevel, Math.min(0.8, Math.max(profile.rms, profile.peak * 0.35)));
  let gain = Math.max(DEFAULTS.minimumGain, Math.min(
    DEFAULTS.maximumGain,
    DEFAULTS.targetRms / Math.max(DEFAULTS.minimumLevel, envelope),
  ));
  const levelAttack = 1 - Math.exp(-1 / (48_000 * DEFAULTS.levelAttackMs / 1000));
  const levelRelease = 1 - Math.exp(-1 / (48_000 * DEFAULTS.levelReleaseMs / 1000));
  const gainDown = 1 - Math.exp(-1 / (48_000 * DEFAULTS.gainDownMs / 1000));
  const gainUp = 1 - Math.exp(-1 / (48_000 * DEFAULTS.gainUpMs / 1000));

  function expected(left, right) {
    const rms = Math.sqrt((left * left + right * right) * 0.5);
    const peak = Math.max(Math.abs(left), Math.abs(right));
    const instant = Math.max(rms, peak * 0.35);
    envelope += (instant - envelope) * (instant > envelope ? levelAttack : levelRelease);
    let desired = Math.max(DEFAULTS.minimumGain, Math.min(
      DEFAULTS.maximumGain,
      DEFAULTS.targetRms / Math.max(DEFAULTS.minimumLevel, envelope),
    ));
    desired = 1 + (desired - 1) * DEFAULTS.levelingStrength;
    gain += (desired - gain) * (desired < gain ? gainDown : gainUp);
    return [left, right].map((sample) => {
      const sign = Math.sign(sample);
      let amount = Math.abs(sample * gain * DEFAULTS.outputLevel);
      if (amount > DEFAULTS.compressorThreshold) {
        amount = DEFAULTS.compressorThreshold
          + (amount - DEFAULTS.compressorThreshold) / DEFAULTS.compressorRatio;
      }
      return sign * Math.min(amount, DEFAULTS.outputCeiling);
    });
  }

  for (const input of [[0.1, -0.1], [0.8, -0.6], [0.02, 0.03], [-1.2, 1.1]]) {
    const actual = dsp.processStereo(...input);
    const wanted = expected(...input);
    assert.ok(Math.abs(actual[0] - wanted[0]) < 1e-12);
    assert.ok(Math.abs(actual[1] - wanted[1]) < 1e-12);
  }
});

test('server PCM DSP processes F32LE stereo buffers and supports bypass', () => {
  const input = Buffer.alloc(16);
  input.writeFloatLE(0.8, 0);
  input.writeFloatLE(-0.8, 4);
  input.writeFloatLE(0.2, 8);
  input.writeFloatLE(-0.2, 12);
  const bypassed = Buffer.from(input);
  new FredPlayerDsp({ enabled: false }).processBuffer(bypassed);
  assert.deepEqual(bypassed, input);

  const processed = Buffer.from(input);
  new FredPlayerDsp({ profile: { rms: 0.1, peak: 0.8 } }).processBuffer(processed);
  assert.notDeepEqual(processed, input);
  assert.ok(Math.abs(processed.readFloatLE(0)) <= DEFAULTS.outputCeiling);
  assert.throws(() => new FredPlayerDsp().processBuffer(Buffer.alloc(7)), /complete/);
});
