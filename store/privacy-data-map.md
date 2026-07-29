# FredPlayer Privacy Data Map

| Feature | Data | Destination | Retention |
| --- | --- | --- | --- |
| Local playback | User-selected audio and metadata | Device only | Until the user removes the file or playlist |
| Leveling and visualization | Derived numeric cache data | Device; optionally the configured Fred Server | Device/server cache policy |
| Bluetooth calibration | Brief microphone samples | Device memory only | Discarded immediately after calibration |
| Speaker timing | Speaker label and delay | Device preferences | Until cleared by the user |
| Fred Server authentication | User-entered server URL and token | Configured server; token also in OS secure storage | Until removed by the user |
| Shared playlists | Playlist name and server track paths | Configured Fred Server | Until the server operator changes/removes it |
| Ask Liam | Prompt and random app-specific identifier | Configured Fred Server | Determined by that server operator |

Store declarations assume the public app connects only to private/self-hosted
servers selected by the user and that Silveron Studios does not operate public
accounts or receive these transfers. Revisit both store declarations before
offering any Silveron Studios-hosted service.

## Store answers

- Apple App Privacy: **Data Not Collected**
- Google Play Data Safety collection question: **Yes**. Google defines
  collection as transmitting data off the device even when Silveron Studios
  does not receive it. Declare the optional Fred Server features below.
- Google Play data types:
  - **Other user-generated content**: Ask Liam prompts, shared-playlist names,
    and server-library track paths; optional; used for app functionality
  - **Device or other identifiers**: random app-specific identifier sent with
    Ask Liam requests; optional; used for app functionality
- Google Play sharing: **No**. These transfers occur only through a specific
  user-initiated action to the private server selected by that user; Google
  lists that case as a sharing exception. Recheck this answer if the server
  model changes.
- Collection is encrypted in transit: **Yes**, because release builds require
  HTTPS.
- Developer data-deletion request mechanism: **No**. Silveron Studios holds no
  server data; local data is user-controlled and server data is controlled by
  the user's chosen operator.
- Tracking: **No**
- Advertising: **No**
- Account creation: **No**
- Data encrypted in transit: production Fred Server connections require HTTPS
