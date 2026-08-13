const DEFAULTS = Object.freeze({
  // Last recorded phone settings. The Web server applies these to decoded
  // PCM before progressively sending the processed audio to the browser.
  outputLevel: 1,
  levelingStrength: 1,
  analysisSeconds: 25,
  levelAttackMs: 15,
  levelReleaseMs: 750,
  gainDownMs: 40,
  gainUpMs: 2800,
  compressorThreshold: 0.43,
  compressorRatio: 6,
  outputCeiling: 0.96,
  targetRms: 0.18,
  minimumLevel: 0.012,
  minimumGain: 0.2,
  maximumGain: 4.5,
});

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

function gainForProfile(profile, settings = DEFAULTS) {
  if (!profile
      || !Number.isFinite(profile.rms)
      || profile.rms <= 0
      || !Number.isFinite(profile.peak)
      || profile.peak < 0) {
    return 1;
  }
  const measured = Math.max(profile.rms, profile.peak * 0.35);
  const desired = clamp(
    settings.targetRms / Math.max(settings.minimumLevel, measured),
    settings.minimumGain,
    settings.maximumGain,
  );
  const leveled = 1 + (desired - 1) * settings.levelingStrength;
  const requestedGain = settings.outputLevel * leveled;

  // HTMLMediaElement volume cannot boost above unity. Preserve unity for a
  // quiet track instead of turning a short peak into whole-track attenuation.
  if (requestedGain >= 1) return 1;

  let protectedPeak = profile.peak * requestedGain;
  if (protectedPeak > settings.compressorThreshold) {
    protectedPeak = settings.compressorThreshold
      + (protectedPeak - settings.compressorThreshold) / settings.compressorRatio;
  }
  protectedPeak = Math.min(protectedPeak, settings.outputCeiling);
  const compressorEquivalentGain = profile.peak > 0
    ? protectedPeak / profile.peak
    : requestedGain;
  return clamp(Math.min(requestedGain, compressorEquivalentGain), 0.05, 1);
}

module.exports = { DEFAULTS, gainForProfile };
