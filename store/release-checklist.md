# FredPlayer Store Release Checklist

## Google Play account

- [ ] Google finishes identity verification for the new personal account
  `rpatrick08@silveronstudios.com`
- [ ] Confirm the public developer name is exactly **Silveron Studios**
- [ ] Create the app with default language English (United States), app type
  App, price Free, and package `com.silveronstudios.fredplayer`
- [ ] Enroll in Play App Signing and create an external upload keystore
- [ ] Upload the signed API-36 `.aab` to internal testing
- [ ] Resolve automated pre-launch report findings
- [ ] Complete Data Safety using the optional Fred Server declarations in
  `privacy-data-map.md`, plus content rating, target audience 13+, ads, app
  access, privacy policy, and media foreground-service declarations
- [ ] Provide a demonstration video for background media playback and the
  microphone disclosure/calibration flow
- [ ] Declare Android Auto and complete the car-app quality review
- [ ] Run a closed test with at least 12 continuously opted-in testers for 14
  days, document their feedback and fixes, then apply for production access
- [ ] Start production with a staged rollout

## Apple follow-up build

- [ ] Leave the currently waiting submission unchanged until Apple responds
- [ ] Confirm the playable-content/CarPlay entitlement is approved for the App
  ID and included in the provisioning profile
- [ ] Supply the privacy manifest and keep App Privacy at Data Not Collected
- [ ] Replace the website privacy text with `privacy-policy.md`
- [ ] Create a new archive from clean Derived Data on the Mac
- [ ] Verify document import, Files sharing, progressive server playback,
  real-time compression, CarPlay, and background controls on physical devices
- [ ] Submit version 1.1 build 2 with the reviewer notes below

## Reviewer access

Both apps now include the original synthesized `FredPlayer Sample` track. Before
either review, provide an isolated HTTPS review Fred Server. The review server must contain only licensed
audio, remain available throughout review, and support library browsing,
progressive playback, visualization/leveling cache reads, shared playlists, and
Ask Liam. Do not reuse a personal token or expose a personal library.

Reviewer notes should include the server URL/token, exact setup steps, where to
find the bundled sample, how microphone calibration works, and that FredPlayer
does not download an entire remote track before playback.

## Release verification

- [ ] Android unit tests, `lintRelease`, and signed `bundleRelease` pass
- [ ] Server tests pass
- [ ] Android phone and tablet layouts respect status/navigation bars
- [ ] Wired, built-in, and Bluetooth playback remain synchronized
- [ ] Android Auto and CarPlay browse, search, metadata, and controls work
- [ ] Privacy and support links open inside both mobile apps
- [ ] Repository and archive contain no unrelated project or assistant-tool
  names, stale build paths, credentials, signing keys, or unlicensed media
