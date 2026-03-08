package io.github.karlmendoo.spoticraft.audio;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.Source;
import net.minecraft.sound.SoundCategory;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LavaPlayerAudioEngine implements AutoCloseable {
    private static final int TRACK_LOAD_TIMEOUT_SECONDS = 30;
    // Keep a small minimum buffer so OpenAL receives enough PCM data per poll even if the caller asks for tiny chunks.
    private static final int MIN_FRAME_BUFFER_SIZE = 2048;
    // Wait briefly for decoded frames so the stream can bridge normal network jitter without stalling the source forever.
    private static final long FRAME_PROVISION_TIMEOUT_MS = 250L;
    private static final AudioFormat PCM_FORMAT = new AudioFormat(48000.0F, 16, 2, true, false);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0);

    private final Logger logger;
    private final DefaultAudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final AudioPlayer player;

    private volatile Listener listener = Listener.NO_OP;
    private volatile Source source;
    private volatile LavaPlayerAudioStream audioStream;
    private volatile int volumePercent = 70;
    private volatile String currentReference = "";

    public LavaPlayerAudioEngine(Logger logger) {
        this.logger = logger;
        this.playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
        this.playerManager.registerSourceManager(new YoutubeAudioSourceManager(true, new MusicWithThumbnail(), new WebWithThumbnail()));
        this.player = this.playerManager.createPlayer();
        this.player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
                listener.onTrackEnded(endReason);
            }

            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                logger.warn("Track playback failed", exception);
                listener.onTrackException(exception.getMessage());
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = Objects.requireNonNullElse(listener, Listener.NO_OP);
    }

    public synchronized LoadedTrack play(String reference, int volumePercent, int startPositionMs) throws IOException {
        AudioTrack track = loadTrack(reference);
        if (startPositionMs > 0 && track.isSeekable()) {
            track.setPosition(startPositionMs);
        }

        stopInternal(false);
        this.volumePercent = volumePercent;
        this.currentReference = reference;

        if (!this.player.startTrack(track, false)) {
            throw new IOException("Failed to start LavaPlayer track.");
        }

        LavaPlayerAudioStream nextStream = new LavaPlayerAudioStream(this.player);
        Source nextSource = createSource();
        try {
            nextSource.setRelative(true);
            nextSource.disableAttenuation();
            nextSource.setPitch(1.0F);
            nextSource.setVolume(effectiveGain());
            nextSource.setStream(nextStream);
            nextSource.play();
        } catch (RuntimeException exception) {
            nextSource.close();
            throw new IOException("Failed to initialize in-game audio playback.", exception);
        }

        this.audioStream = nextStream;
        this.source = nextSource;
        return LoadedTrack.from(track.getInfo(), reference);
    }

    public synchronized void pause() {
        if (this.player.getPlayingTrack() == null) {
            return;
        }
        this.player.setPaused(true);
        if (this.source != null) {
            this.source.pause();
        }
    }

    public synchronized void resume() {
        if (this.player.getPlayingTrack() == null) {
            return;
        }
        this.player.setPaused(false);
        if (this.source != null) {
            this.source.resume();
        }
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    public synchronized void seekTo(int positionMs) {
        AudioTrack track = this.player.getPlayingTrack();
        if (track != null && track.isSeekable()) {
            track.setPosition(Math.max(0, positionMs));
        }
    }

    public void tick() {
        Source currentSource = this.source;
        if (currentSource == null) {
            return;
        }
        currentSource.setVolume(effectiveGain());
        currentSource.tick();
        if (currentSource.isStopped() && this.player.getPlayingTrack() == null) {
            stopInternal(false);
        }
    }

    public int currentPositionMs() {
        AudioTrack track = this.player.getPlayingTrack();
        return track == null ? 0 : (int) Math.max(0L, track.getPosition());
    }

    public int currentDurationMs() {
        AudioTrack track = this.player.getPlayingTrack();
        return track == null ? 1 : (int) Math.max(1L, track.getDuration());
    }

    public boolean hasTrack() {
        return this.player.getPlayingTrack() != null;
    }

    public boolean isPlaying() {
        return hasTrack() && !this.player.isPaused();
    }

    public void setVolume(int volumePercent) {
        this.volumePercent = Math.max(0, Math.min(volumePercent, 100));
        Source currentSource = this.source;
        if (currentSource != null) {
            currentSource.setVolume(effectiveGain());
        }
    }

    public String currentReference() {
        return this.currentReference;
    }

    @Override
    public synchronized void close() {
        stopInternal(true);
        this.player.destroy();
        this.playerManager.shutdown();
    }

    private AudioTrack loadTrack(String reference) throws IOException {
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        this.playerManager.loadItemOrdered(this, reference, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack selected = selectTrack(playlist);
                if (selected == null) {
                    future.completeExceptionally(new IOException("Playlist contained no playable tracks."));
                    return;
                }
                future.complete(selected);
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new IOException("No playable audio stream was found for " + reference + '.'));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });

        try {
            return future.get(TRACK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading audio track.", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Failed to load audio track.", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IOException("Timed out while loading audio track.", exception);
        }
    }

    private synchronized void stopInternal(boolean notifyListener) {
        Source currentSource = this.source;
        this.source = null;
        LavaPlayerAudioStream currentStream = this.audioStream;
        this.audioStream = null;
        this.currentReference = "";

        if (currentSource != null) {
            currentSource.stop();
            currentSource.close();
        }
        if (currentStream != null) {
            currentStream.close();
        }
        this.player.stopTrack();
        if (notifyListener) {
            this.listener.onTrackEnded(AudioTrackEndReason.STOPPED);
        }
    }

    private float effectiveGain() {
        MinecraftClient client = MinecraftClient.getInstance();
        float recordsVolume = client == null ? 1.0F : client.options.getSoundVolume(SoundCategory.RECORDS);
        float configuredGain = (this.volumePercent / 100.0F) * recordsVolume;
        return Math.max(0.0F, Math.min(1.0F, configuredGain));
    }

    private Source createSource() throws IOException {
        try {
            return Source.create();
        } catch (RuntimeException exception) {
            throw new IOException("Failed to create Minecraft audio source.", exception);
        }
    }

    private static final class LavaPlayerAudioStream implements AudioStream {
        private final AudioPlayer player;
        private final MutableAudioFrame mutableFrame = new MutableAudioFrame();
        private volatile boolean closed;

        private LavaPlayerAudioStream(AudioPlayer player) {
            this.player = player;
        }

        @Override
        public AudioFormat getFormat() {
            return PCM_FORMAT;
        }

        @Override
        public ByteBuffer read(int bufferSize) throws IOException {
            if (this.closed) {
                return EMPTY_BUFFER.duplicate();
            }

            int size = Math.max(MIN_FRAME_BUFFER_SIZE, bufferSize);
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            this.mutableFrame.setBuffer(buffer);
            try {
                boolean provided = this.player.provide(this.mutableFrame, FRAME_PROVISION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!provided) {
                    return EMPTY_BUFFER.duplicate();
                }
            } catch (TimeoutException exception) {
                return EMPTY_BUFFER.duplicate();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while streaming audio frame.", exception);
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private static AudioTrack selectTrack(AudioPlaylist playlist) {
        AudioTrack selected = playlist.getSelectedTrack();
        if (selected != null) {
            return selected;
        }
        return playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0);
    }

    private static int normalizeDuration(long durationMs) {
        if (durationMs <= 0L || durationMs > Integer.MAX_VALUE) {
            return 1;
        }
        return (int) durationMs;
    }

    private static String nonBlankOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public interface Listener {
        Listener NO_OP = new Listener() {
            @Override
            public void onTrackEnded(AudioTrackEndReason endReason) {
            }

            @Override
            public void onTrackException(String message) {
            }
        };

        void onTrackEnded(AudioTrackEndReason endReason);

        void onTrackException(String message);
    }

    public record LoadedTrack(
        String title,
        String author,
        int durationMs,
        String uri,
        String artworkUrl
    ) {
        private static LoadedTrack from(AudioTrackInfo info, String reference) {
            return new LoadedTrack(
                nonBlankOrEmpty(info.title),
                nonBlankOrEmpty(info.author),
                normalizeDuration(info.length),
                nonBlankOrEmpty(info.uri).isBlank() ? reference : info.uri,
                nonBlankOrEmpty(info.artworkUrl)
            );
        }
    }
}
