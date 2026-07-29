# FredPlayer Privacy Policy

Last updated: July 29, 2026

FredPlayer is published by Silveron Studios. Silveron Studios does not collect,
store, sell, or share personal information through the FredPlayer mobile apps.
FredPlayer contains no advertising, analytics, tracking, or third-party data
collection SDKs, and it does not require a FredPlayer account.

## Information kept on the device

FredPlayer stores playlists, playback settings, audio-analysis results,
visualization data, an app-specific random identifier, and optional Bluetooth
speaker timing adjustments on the device. Server access tokens are protected by
the operating system's secure credential storage. Audio leveling,
visualization, and Bluetooth delay measurement are performed locally.

The optional Bluetooth calibration feature uses the microphone only after the
user starts calibration and grants permission. It listens for a short sound
played by FredPlayer, calculates a timing adjustment, and immediately discards
the microphone samples. No recording or calibration data is uploaded.

## User-selected audio

FredPlayer accesses only audio files and folders selected by the user or placed
in FredPlayer's documents area. Those audio files are not uploaded to Silveron
Studios. Users are responsible for playing or connecting only audio they are
authorized to use.

## Optional Fred Server connections

Users may configure FredPlayer to connect to a private, self-hosted Fred Server.
Silveron Studios does not provide public Fred Server accounts and does not
receive data sent to a user-selected server. Depending on the features the user
chooses, the app may send that server:

- The configured access token for authentication
- Requests for audio, metadata, visualization data, and leveling data
- Locally calculated visualization or leveling cache data
- Shared-playlist names and server-library track paths
- Ask Liam prompts and a random, app-specific device identifier

The Fred Server operator controls that server's storage, logs, retention,
security, and any services configured behind Ask Liam. The operator may see the
device's network address as part of ordinary server operation. Users should
review the policies of their chosen server operator and can stop all transfers
by removing the Fred Server URL and token from FredPlayer.

Deleting a downloaded shared playlist removes the local copy but does not
delete the independently published copy from the configured Fred Server.
Server-side copies must be managed by that server's operator.

## Contact

Questions and support requests can be submitted at
https://patrick-lamphier.com/fredplayer-support or emailed to
webmaster@silveronstudios.com.
