# 🎵 SpotiCraft

A complete Spotify integration mod for **Minecraft 1.21.11** (Fabric). Control your Spotify music without ever tabbing out of the game — featuring a polished in-game UI, keybinds, HUD overlay, and full playback control.

---

## ✨ Features

- **Spotify OAuth 2.0 PKCE** authentication — no client secret required
- **Full playback controls**: Play/Pause, Next, Previous, Seek, Shuffle, Repeat
- **Now Playing display**: Album art, track info, animated progress bar
- **Library browser**: Your playlists, liked songs, recently played tracks
- **Search**: Find songs, albums, artists, and playlists
- **Track change notifications**: Toast popup when a new song starts
- **HUD overlay**: Small corner widget with current track info
- **Keybinds**: `M` to open UI, configurable keys for playback controls

---

## 📦 Requirements

| Component | Version |
|-----------|---------|
| Minecraft | **1.21.11** |
| Fabric Loader | 0.16.9+ |
| Fabric API | 0.119.5+1.21.11 |
| Java | 21 |

---

## 🔧 Setup

### 1. Create a Spotify Developer App

1. Go to [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Log in and click **Create App**
3. Fill in:
   - **App name**: SpotiCraft (or anything you like)
   - **App description**: Minecraft Spotify mod
   - **Redirect URI**: `http://localhost:4381/callback`
4. Click **Save**, then open the app and click **Settings**
5. Copy your **Client ID**

### 2. Configure SpotiCraft

1. Install the mod in your `mods/` folder
2. Launch Minecraft 1.21.11 with Fabric
3. Press **`M`** to open SpotiCraft
4. Go to the **Settings** tab
5. Paste your **Spotify Client ID** and click **Save**
6. Click **Connect to Spotify** — your browser will open
7. Authorize the app in your browser
8. Return to Minecraft — you're connected!

Tokens are saved automatically so you won't need to re-authenticate each launch.

---

## 🎮 Keybinds

| Key | Action |
|-----|--------|
| `M` | Open/Close SpotiCraft UI |
| Configurable | Play/Pause |
| Configurable | Next Track |
| Configurable | Previous Track |
| Configurable | Volume Up (+10%) |
| Configurable | Volume Down (-10%) |

Configure keys in **Options → Controls → SpotiCraft**.

---

## 🏗️ Building from Source

```bash
git clone https://github.com/karlmendoo/spoticraft.git
cd spoticraft
./gradlew build
```

The compiled mod JAR will be in `build/libs/`.

---

## 🔐 Authentication Flow (PKCE)

1. User enters their Spotify Client ID in Settings
2. User clicks "Connect to Spotify"
3. Mod generates a `code_verifier` (random 128-char string) and `code_challenge` (SHA-256 + base64url)
4. Mod starts a lightweight HTTP server on `localhost:4381`
5. Mod opens the user's browser to Spotify's authorization URL
6. User logs in and authorizes the app in their browser
7. Spotify redirects to `http://localhost:4381/callback?code=...`
8. The embedded server captures the auth code and shuts down
9. Mod exchanges the auth code + `code_verifier` for an `access_token` and `refresh_token`
10. Tokens are stored in `.minecraft/config/spoticraft.json`
11. Before each API call, the mod checks if the token is expired and refreshes automatically

---

## 📁 Project Structure

```
src/main/java/com/spoticraft/
├── SpotiCraftMod.java              Main initializer
├── SpotiCraftClient.java           Client initializer (keybinds, HUD)
├── api/
│   ├── SpotifyAPI.java             All Spotify REST API calls
│   ├── SpotifyAuth.java            OAuth PKCE flow & token management
│   ├── AuthCallbackServer.java     Localhost HTTP server for OAuth redirect
│   └── models/
│       ├── Track.java
│       ├── Playlist.java
│       ├── Album.java
│       ├── Artist.java
│       ├── SearchResult.java
│       └── PlaybackState.java
├── config/
│   └── SpotiCraftConfig.java       Config storage (client ID, tokens, prefs)
├── ui/
│   ├── SpotiCraftScreen.java       Main screen with sidebar navigation
│   ├── NowPlayingWidget.java       Now playing panel with controls
│   ├── PlaylistListWidget.java     Scrollable playlist/track browser
│   ├── SearchWidget.java           Search bar + categorized results
│   ├── TrackListWidget.java        Generic track list component
│   ├── VolumeSliderWidget.java     Volume control slider
│   ├── SettingsWidget.java         Settings tab (auth, preferences)
│   ├── HudOverlay.java             Corner now-playing HUD
│   └── ToastNotification.java      Track-change toast popup
└── util/
    ├── AlbumArtCache.java          Async album art download + MC texture cache
    ├── HttpHelper.java             Shared async HTTP utilities
    └── TimeFormatter.java          ms → mm:ss formatter
```

---

## ⚠️ Notes

- **Spotify Premium required** for playback control (Spotify API restriction)
- The mod is **client-side only** — works in singleplayer and multiplayer
- All Spotify API calls run on **background threads** — the game never freezes
- Config stored at: `.minecraft/config/spoticraft.json`

---

## 📜 License

MIT License — see [LICENSE](LICENSE) for details.

