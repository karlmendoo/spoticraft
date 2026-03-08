# 🎵 SpotiCraft

SpotiCraft is a Fabric mod for **Minecraft 1.21.11** that keeps the existing in-game music UI and now powers it with **YouTube** plus **LavaPlayer** for real in-game audio playback. You can connect a Google account, browse your YouTube playlists and liked videos, search the catalog, and keep the same polished in-game player flow without tabbing through menus.

## Features

- Google / YouTube account connection with OAuth in the browser
- In-game playback controls: play, pause, previous, next, shuffle, repeat
- Current media panel with title, creator, collection, thumbnail, progress, in-game player status, and volume slider
- Library browsing for playlists, liked videos, and recently played items
- Search for videos, channels, playlists, and collection-style playlists
- Media-change HUD toast with thumbnail artwork
- Keyboard shortcuts for quick playback control
- Clean, layered UI with light transparency, accent bars, spacing, and premium card-style panels

## Keybinds

- **O** — Open SpotiCraft
- **J** — Previous video
- **K** — Play / Pause
- **L** — Next video

## Setup

1. Create a Google OAuth client and enable the **YouTube Data API v3** for that project.
2. Add this redirect URI to the OAuth client settings:
   - `http://127.0.0.1:43897/callback`
3. Run Minecraft once with the mod installed so SpotiCraft creates its config file.
4. Open the config file at:
   - `.minecraft/config/spoticraft.json`
5. Fill in these values:

```json
{
  "clientId": "your-google-oauth-client-id",
  "clientSecret": "your-google-oauth-client-secret-if-required",
  "redirectUri": "http://127.0.0.1:43897/callback"
}
```

6. Launch the game, open SpotiCraft with **O**, then click **Connect**.
7. Approve the Google / YouTube permissions in your browser and return to Minecraft.
8. Choose a video or playlist in-game; SpotiCraft resolves the stream with LavaPlayer and plays the audio directly inside Minecraft.

## Authentication flow

SpotiCraft uses Google's **OAuth 2.0 installed application** flow.

- Minecraft opens the Google authorization page in the browser.
- Google redirects back to a small temporary localhost callback server inside the mod.
- The mod exchanges the authorization code for an access token and refresh token.
- Tokens are stored locally in `spoticraft.json` so the session can be refreshed later.
- If the token expires or YouTube returns `401`, SpotiCraft clears the session and asks the player to reconnect.

## Playback model

- YouTube metadata, playlists, liked videos, search results, thumbnails, and durations come from the YouTube Data API.
- Playback is decoded and streamed directly inside Minecraft using LavaPlayer.
- SpotiCraft keeps the queue, progress, repeat, shuffle, overlay state, and thumbnail artwork in sync with the in-game audio player so the current UI flow remains intact.

## Building

```bash
./gradlew build
```

Use Java 21 for building and running the project.

## Dependencies

- Fabric Loader `0.18.4`
- Fabric API `0.139.4+1.21.11`
- Yarn mappings `1.21.11+build.4`
- Fabric Loom `1.14.10`

## Project structure

- `src/main/java/io/github/karlmendoo/spoticraft/SpotiCraftClient.java` — client entrypoint, keybinds, periodic syncing
- `src/main/java/io/github/karlmendoo/spoticraft/config/SpotiCraftConfig.java` — config, token persistence, recent-history persistence
- `src/main/java/io/github/karlmendoo/spoticraft/youtube/` — Google OAuth, YouTube Data API calls, service logic, error handling
- `src/main/java/io/github/karlmendoo/spoticraft/ui/` — screen layout, thumbnail cache, overlay
- `src/main/resources/fabric.mod.json` — Fabric mod metadata

## Notes

- The mod preserves the existing UI structure and uses YouTube for backend data and queueing.
- Playlists and liked videos come from the authenticated YouTube account; recently played items are persisted locally by the mod.
- Channel search results are shown for discovery, while direct playback focuses on videos and playlists.
