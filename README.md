# 🎵 SpotiCraft

**Spotify integration mod for Minecraft 1.21.1 (Fabric)**

Control your Spotify music directly from inside Minecraft — no more tabbing out!

## Features

- **Playback Controls** — Play, pause, skip, previous, shuffle, and repeat
- **Now Playing** — See current track name, artist, album, and playback progress
- **Progress & Volume** — Drag to seek and adjust volume in-game
- **Browse** — View your playlists, liked songs, and recently played tracks
- **Search** — Find songs, albums, artists, and playlists without leaving the game
- **Track Notifications** — HUD overlay with fade animation when the track changes
- **Keybinds** — Quick controls: open player (`M`), play/pause, next, previous (configurable)
- **Clean UI** — Dark themed panels with Spotify green accents, smooth hover effects

## Setup Instructions

### 1. Prerequisites

- Minecraft 1.21.1 with [Fabric Loader](https://fabricmc.net/) (>= 0.16.0)
- [Fabric API](https://modrinth.com/mod/fabric-api) installed
- Java 21
- A Spotify account (Premium recommended for full playback control)

### 2. Create a Spotify App

1. Go to the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Click **Create App**
3. Set the **Redirect URI** to: `http://localhost:25921/callback`
4. Note your **Client ID** (you'll need it in-game)
5. Make sure PKCE is enabled in your app settings

### 3. Install the Mod

1. Build the mod with `./gradlew build` (or download a release)
2. Copy `build/libs/spoticraft-1.0.0.jar` into your `.minecraft/mods/` folder
3. Launch Minecraft with Fabric

### 4. First-Time Setup (In-Game)

1. Press **M** (default) to open SpotiCraft
2. Enter your Spotify **Client ID** and click **Save & Connect**
3. Click **Connect Spotify** — your browser will open for authorization
4. Log in and approve the permissions
5. Return to Minecraft — you're connected!

## Keybinds

| Action        | Default Key | Configurable |
|---------------|-------------|--------------|
| Open Player   | M           | Yes          |
| Play/Pause    | Unbound     | Yes          |
| Next Track    | Unbound     | Yes          |
| Previous Track| Unbound     | Yes          |

All keybinds can be changed in Minecraft's Controls settings under the **SpotiCraft** category.

## How Authentication Works

SpotiCraft uses **OAuth 2.0 with PKCE** (Proof Key for Code Exchange), the most secure method for public clients:

1. When you click "Connect Spotify", the mod generates a cryptographic code verifier and challenge
2. Your browser opens the Spotify authorization page
3. A temporary local HTTP server starts on port `25921` to receive the callback
4. After you authorize, Spotify redirects back with an authorization code
5. The mod exchanges the code (plus the PKCE verifier) for access and refresh tokens
6. Tokens are stored locally in `config/spoticraft.json`
7. The access token auto-refreshes when it expires — no need to re-authorize

**No client secret is needed** — PKCE is designed specifically for this use case.

## Dependencies

| Dependency   | Version        | Purpose                    |
|-------------|----------------|----------------------------|
| Fabric Loader| >= 0.16.0     | Mod loader                 |
| Fabric API   | 0.115.0+1.21.1| Keybinds, HUD rendering    |
| Minecraft    | 1.21.1         | Game                       |
| Gson         | (bundled)      | JSON parsing (ships with MC)|
| Java HttpClient | (JDK 21)  | Spotify API requests       |

## Project Structure

```
src/
├── main/java/.../spoticraft/
│   └── SpotiCraft.java              # Common entrypoint placeholder
├── main/resources/
│   └── fabric.mod.json              # Mod metadata
└── client/java/.../spoticraft/
    ├── SpotiCraftClient.java         # Client entrypoint
    ├── config/
    │   └── SpotiCraftConfig.java     # Persistent config (tokens, prefs)
    ├── spotify/
    │   ├── SpotifyAuthManager.java   # OAuth2 PKCE authentication
    │   ├── SpotifyAPI.java           # Spotify Web API client
    │   └── SpotifyPlayer.java        # Playback state polling
    ├── ui/
    │   ├── screen/
    │   │   ├── SpotifyPlayerScreen.java  # Main player UI
    │   │   ├── SpotifyBrowseScreen.java  # Browse playlists/liked/recent
    │   │   └── SpotifySearchScreen.java  # Search Spotify
    │   ├── widget/
    │   │   └── SpotiCraftButton.java     # Custom styled button
    │   └── overlay/
    │       └── TrackChangeOverlay.java   # Track change HUD notification
    └── keybind/
        └── KeyBindManager.java       # Keybind registration
```

## Building from Source

```bash
git clone https://github.com/karlmendoo/spoticraft.git
cd spoticraft
./gradlew build
```

The built JAR will be at `build/libs/spoticraft-1.0.0.jar`.

## License

MIT License — see [LICENSE](LICENSE) for details.
