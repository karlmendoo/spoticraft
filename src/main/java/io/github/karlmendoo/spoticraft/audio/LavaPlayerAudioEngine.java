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
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.TvHtml5SimplyWithThumbnail;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.Source;
import net.minecraft.sound.SoundCategory;
import io.github.karlmendoo.spoticraft.youtube.model.PlaybackState;
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
    // One COMMON_PCM_S16_LE frame: 960 samples/channel × 2 channels × 2 bytes/sample = 3840 bytes (20 ms at 48 kHz).
    private static final int LAVA_FRAME_SIZE = 960 * 2 * 2;
    // Wait briefly for decoded frames so the stream can bridge normal network jitter without stalling the source forever.
    private static final long FRAME_PROVISION_TIMEOUT_MS = 250L;
    private static final long BUFFERING_THRESHOLD_MS = 1_500L;
    private static final long STALL_TIMEOUT_MS = 10_000L;
    private static final long BUFFERING_TIMEOUT_MS = 30_000L;
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
    private volatile PlaybackState playbackState = PlaybackState.IDLE;
    private volatile int renderedPositionMs;
    private volatile long lastFrameAtMs;
    private volatile long bufferingStartedAtMs;
    private volatile String lastFailureMessage = "";

    public LavaPlayerAudioEngine(Logger logger) {
        this.logger = logger;
        this.playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
        this.playerManager.registerSourceManager(new YoutubeAudioSourceManager(
            true,
            new MusicWithThumbnail(),
            new AndroidVrWithThumbnail(),
            new TvHtml5SimplyWithThumbnail(),
            new WebEmbeddedWithThumbnail(),
            new WebWithThumbnail()
        ));
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
        this.renderedPositionMs = Math.max(0, startPositionMs);
        this.lastFrameAtMs = System.currentTimeMillis();
        this.bufferingStartedAtMs = System.currentTimeMillis();
        this.lastFailureMessage = "";
        this.playbackState = PlaybackState.BUFFERING;

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
        this.renderedPositionMs = currentPositionMs();
        this.playbackState = PlaybackState.PAUSED;
        if (this.source != null) {
            try {
                this.source.pause();
            } catch (RuntimeException exception) {
                this.logger.warn("Audio source pause failed.", exception);
            }
        }
    }

    public synchronized void resume() {
        if (this.player.getPlayingTrack() == null) {
            return;
        }
        this.player.setPaused(false);
        this.lastFrameAtMs = System.currentTimeMillis();
        this.bufferingStartedAtMs = System.currentTimeMillis();
        this.playbackState = PlaybackState.BUFFERING;
        if (this.source != null) {
            try {
                this.source.resume();
            } catch (RuntimeException exception) {
                this.logger.warn("Audio source resume failed.", exception);
            }
        }
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    public synchronized void seekTo(int positionMs) {
        AudioTrack track = this.player.getPlayingTrack();
        if (track != null && track.isSeekable()) {
            int clamped = Math.max(0, positionMs);
            track.setPosition(clamped);
            this.renderedPositionMs = clamped;
            long now = System.currentTimeMillis();
            this.lastFrameAtMs = now;
            if (!this.player.isPaused()) {
                this.playbackState = PlaybackState.BUFFERING;
                this.bufferingStartedAtMs = now;
            }
        }
    }

    public void tick() {
        Source currentSource = this.source;
        AudioTrack track = this.player.getPlayingTrack();
        if (currentSource == null) {
            if (track == null && this.playbackState == PlaybackState.PLAYING) {
                this.playbackState = PlaybackState.STOPPED;
            }
            return;
        }
        try {
            currentSource.setVolume(effectiveGain());
            currentSource.tick();
        } catch (RuntimeException exception) {
            this.logger.warn("Audio source tick failed, stopping playback.", exception);
            failPlayback("Audio source became invalid during playback.");
            return;
        }
        if (track == null) {
            stopInternal(false);
            this.playbackState = PlaybackState.STOPPED;
            return;
        }
        if (this.player.isPaused()) {
            this.playbackState = PlaybackState.PAUSED;
            return;
        }
        long now = System.currentTimeMillis();
        long silentForMs = now - this.lastFrameAtMs;
        boolean sourceStopped;
        try {
            sourceStopped = currentSource.isStopped();
        } catch (RuntimeException exception) {
            this.logger.warn("Audio source state query failed.", exception);
            sourceStopped = true;
        }
        if (sourceStopped) {
            if (silentForMs >= STALL_TIMEOUT_MS) {
                failPlayback("Playback output stopped unexpectedly.");
                return;
            }
            enterBuffering(now);
            return;
        }
        if (silentForMs >= STALL_TIMEOUT_MS) {
            failPlayback("Playback stalled while streaming audio.");
            return;
        }
        if (silentForMs >= BUFFERING_THRESHOLD_MS) {
            enterBuffering(now);
            return;
        }
        this.playbackState = PlaybackState.PLAYING;
        this.bufferingStartedAtMs = 0L;
    }

    private void enterBuffering(long now) {
        if (this.playbackState != PlaybackState.BUFFERING) {
            this.bufferingStartedAtMs = now;
        }
        this.playbackState = PlaybackState.BUFFERING;
        if (this.bufferingStartedAtMs > 0L && now - this.bufferingStartedAtMs >= BUFFERING_TIMEOUT_MS) {
            failPlayback("Buffering timed out after " + (BUFFERING_TIMEOUT_MS / 1000L) + " seconds.");
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
        return this.playbackState == PlaybackState.PLAYING;
    }

    public PlaybackState playbackState() {
        return this.playbackState;
    }

    public int renderedPositionMs() {
        return hasTrack() ? this.renderedPositionMs : 0;
    }

    public String lastFailureMessage() {
        return this.lastFailureMessage;
    }

    public void setVolume(int volumePercent) {
        this.volumePercent = Math.max(0, Math.min(volumePercent, 100));
        Source currentSource = this.source;
        if (currentSource != null) {
            try {
                currentSource.setVolume(effectiveGain());
            } catch (RuntimeException exception) {
                this.logger.warn("Audio source setVolume failed.", exception);
            }
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
        this.renderedPositionMs = 0;
        this.bufferingStartedAtMs = 0L;

        if (currentSource != null) {
            try {
                currentSource.stop();
                currentSource.close();
            } catch (RuntimeException exception) {
                this.logger.warn("Failed to close audio source during stop.", exception);
            }
        }
        if (currentStream != null) {
            currentStream.close();
        }
        this.player.stopTrack();
        if (this.playbackState != PlaybackState.ERROR) {
            this.playbackState = PlaybackState.STOPPED;
        }
        if (notifyListener) {
            this.listener.onTrackEnded(AudioTrackEndReason.STOPPED);
        }
    }

    private synchronized void frameProvided() {
        this.lastFrameAtMs = System.currentTimeMillis();
        this.renderedPositionMs = currentPositionMs();
        this.bufferingStartedAtMs = 0L;
        if (this.player.getPlayingTrack() != null && !this.player.isPaused()) {
            this.playbackState = PlaybackState.PLAYING;
        }
    }

    private synchronized void frameUnavailable() {
        if (this.player.getPlayingTrack() != null && !this.player.isPaused() && this.playbackState != PlaybackState.ERROR) {
            this.playbackState = PlaybackState.BUFFERING;
        }
    }

    private void failPlayback(String message) {
        this.lastFailureMessage = message;
        this.playbackState = PlaybackState.ERROR;
        stopInternal(false);
        this.listener.onTrackException(message);
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

    private final class LavaPlayerAudioStream implements AudioStream {
        private final AudioPlayer player;
        private final MutableAudioFrame mutableFrame = new MutableAudioFrame();
        private final ByteBuffer frameBuffer = ByteBuffer.allocateDirect(LAVA_FRAME_SIZE);
        private volatile boolean closed;
        private ByteBuffer silenceBuffer = ByteBuffer.allocateDirect(LAVA_FRAME_SIZE);

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

            int size = Math.max(LAVA_FRAME_SIZE, bufferSize);
            ByteBuffer output = ByteBuffer.allocateDirect(size);
            boolean anyProvided = false;

            while (output.remaining() >= LAVA_FRAME_SIZE) {
                this.frameBuffer.clear();
                this.mutableFrame.setBuffer(this.frameBuffer);
                try {
                    long timeout = anyProvided ? 0L : FRAME_PROVISION_TIMEOUT_MS;
                    boolean provided = this.player.provide(this.mutableFrame, timeout, TimeUnit.MILLISECONDS);
                    if (!provided) {
                        break;
                    }
                } catch (TimeoutException exception) {
                    break;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while streaming audio frame.", exception);
                }
                this.frameBuffer.flip();
                if (!this.frameBuffer.hasRemaining()) {
                    break;
                }
                output.put(this.frameBuffer);
                anyProvided = true;
            }

            output.flip();
            if (output.hasRemaining()) {
                frameProvided();
                return output;
            }
            frameUnavailable();
            return silenceBuffer(size);
        }

        @Override
        public void close() {
            this.closed = true;
        }

        private synchronized ByteBuffer silenceBuffer(int size) {
            if (this.silenceBuffer.capacity() < size) {
                this.silenceBuffer = ByteBuffer.allocateDirect(size);
            }
            this.silenceBuffer.clear();
            this.silenceBuffer.position(size);
            this.silenceBuffer.flip();
            return this.silenceBuffer.duplicate();
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
