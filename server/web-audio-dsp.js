'use strict';

const { DEFAULTS } = require('./web-leveling.js');

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

function finite(value, fallback) {
  return Number.isFinite(value) ? value : fallback;
}

class FredPlayerDsp {
  constructor({ sampleRate = 48_000, profile = null, enabled = true, settings = {} } = {}) {
    this.sampleRate = Math.max(1, finite(sampleRate, 48_000));
    this.enabled = Boolean(enabled);
    this.settings = { ...DEFAULTS, ...settings };
    this.levelAttack = this.coefficient(this.settings.levelAttackMs / 1000);
    this.levelRelease = this.coefficient(this.settings.levelReleaseMs / 1000);
    this.gainDown = this.coefficient(this.settings.gainDownMs / 1000);
    this.gainUp = this.coefficient(this.settings.gainUpMs / 1000);
    this.envelope = this.settings.targetRms;
    this.gain = 1;

    if (Number.isFinite(profile?.rms) && Number.isFinite(profile?.peak)) {
      const measured = Math.max(profile.rms, profile.peak * 0.35);
      this.envelope = clamp(measured, this.settings.minimumLevel, 0.8);
      this.gain = clamp(
        this.settings.targetRms / Math.max(this.settings.minimumLevel, this.envelope),
        this.settings.minimumGain,
        this.settings.maximumGain,
      );
    }
  }

  coefficient(seconds) {
    return 1 - Math.exp(-1 / (this.sampleRate * Math.max(0.000001, seconds)));
  }

  protect(value) {
    const sign = Math.sign(value);
    let amount = Math.abs(value);
    if (amount > this.settings.compressorThreshold) {
      amount = this.settings.compressorThreshold
        + (amount - this.settings.compressorThreshold) / this.settings.compressorRatio;
    }
    return sign * Math.min(amount, this.settings.outputCeiling);
  }

  processStereo(left, right) {
    if (!this.enabled) return [left, right];

    const rms = Math.sqrt((left * left + right * right) * 0.5);
    const peak = Math.max(Math.abs(left), Math.abs(right));
    const instant = Math.max(rms, peak * 0.35);
    const levelAlpha = instant > this.envelope ? this.levelAttack : this.levelRelease;
    this.envelope += (instant - this.envelope) * levelAlpha;

    let desired = clamp(
      this.settings.targetRms / Math.max(this.settings.minimumLevel, this.envelope),
      this.settings.minimumGain,
      this.settings.maximumGain,
    );
    desired = 1 + (desired - 1) * clamp(this.settings.levelingStrength, 0, 1);
    const gainAlpha = desired < this.gain ? this.gainDown : this.gainUp;
    this.gain += (desired - this.gain) * gainAlpha;

    const scale = this.gain * this.settings.outputLevel;
    return [this.protect(left * scale), this.protect(right * scale)];
  }

  processBuffer(buffer) {
    if (!Buffer.isBuffer(buffer) || buffer.length % 8 !== 0) {
      throw new TypeError('PCM buffer must contain complete interleaved F32LE stereo frames');
    }
    for (let offset = 0; offset < buffer.length; offset += 8) {
      const [left, right] = this.processStereo(
        buffer.readFloatLE(offset),
        buffer.readFloatLE(offset + 4),
      );
      buffer.writeFloatLE(left, offset);
      buffer.writeFloatLE(right, offset + 4);
    }
    return buffer;
  }
}

module.exports = { FredPlayerDsp };
