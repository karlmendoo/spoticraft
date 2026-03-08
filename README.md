# 🎵 SpotiCraft

SpotiCraft is a Fabric mod for **Minecraft 1.21.11** that brings Spotify controls directly into the game with a polished in-world UI. It lets you connect your Spotify account, browse your library, search the catalog, control playback, and see stylish track-change overlays without tabbing out.

## Features

- Spotify account connection with OAuth in the browser
- In-game playback controls: play, pause, previous, next, shuffle, repeat
- Current song panel with title, artist, album, album art, progress, device, and volume slider
- Library browsing for playlists, liked songs, and recently played tracks
- Search for tracks, albums, artists, and playlists
- Track-change HUD toast with album art
- Keyboard shortcuts for quick playback control
- Clean, layered UI with light transparency, accent bars, spacing, and premium card-style panels

## Keybinds

- **O** — Open SpotiCraft
- **J** — Previous track
- **K** — Play / Pause
- **L** — Next track

## Setup

1. Create a Spotify app at the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Add this redirect URI to the app settings:
   - `http://127.0.0.1:43897/callback`
3. Run Minecraft once with the mod installed so SpotiCraft creates its config file.
4. Open the config file at:
   - `.minecraft/config/spoticraft.json`
5. Fill in these values:

```json
{
  "clientId": "your-spotify-client-id",
  "clientSecret": "your-spotify-client-secret",
  "redirectUri": "http://127.0.0.1:43897/callback"
}
```

6. Launch the game, open SpotiCraft with **O**, then click **Connect**.
7. Approve the Spotify permissions in your browser and return to Minecraft.
8. Start playback on any Spotify device if Spotify reports that no active device is connected.

## Authentication flow

SpotiCraft uses Spotify's **Authorization Code** OAuth flow.

- Minecraft opens the Spotify authorization page in the browser.
- Spotify redirects back to a small temporary localhost callback server inside the mod.
- The mod exchanges the authorization code for an access token and refresh token.
- Tokens are stored locally in `spoticraft.json` so the session can be refreshed later.
- If the token expires or Spotify returns `401`, SpotiCraft clears the session and asks the player to reconnect.

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
- `src/main/java/io/github/karlmendoo/spoticraft/config/SpotiCraftConfig.java` — config and token persistence
- `src/main/java/io/github/karlmendoo/spoticraft/spotify/` — OAuth, Spotify Web API calls, service logic, error handling
- `src/main/java/io/github/karlmendoo/spoticraft/ui/` — screen layout, album art cache, overlay
- `src/main/resources/fabric.mod.json` — Fabric mod metadata

## Notes

- Spotify playback control requires a Spotify Premium account and an active playback device.
- Artist search results are displayed for discovery, but direct playback is focused on tracks, albums, and playlists.
- The UI is intentionally client-side only and does not require a separate server component.
