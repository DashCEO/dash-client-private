package com.dash.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jcodec.api.FrameGrab;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.AWTUtil;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class DashIntroVideo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("dash-client", "intro_video");
    private static final String INTRO = "Intro.mp4";
    private static final String STARTUP = "Startup.mp4";
    private static final long GUIDE_START_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long HOLD_TO_SKIP_NANOS = TimeUnit.MILLISECONDS.toNanos(1500);
    private static final int REORDER_LOOKAHEAD = 3;
    private static final int PREBUFFERED_FRAMES = 12;
    private static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(8);

    private static Playback playback;
    private static boolean guideStarted;
    private static long holdStart = Long.MIN_VALUE;
    private static boolean finished = Boolean.getBoolean("dash.skipStartupIntro");

    private DashIntroVideo() {
    }

    public static boolean render(GuiGraphics graphics) {
        if (finished) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        if (SaveMode.isPaused() || InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            finish(client);
            return false;
        }

        try {
            if (playback == null) {
                float volume = client.options.getSoundSourceVolume(SoundSource.MASTER);
                String resource = DashIntroVideo.class.getResource(STARTUP) != null
                        && Files.notExists(guideMarker()) ? STARTUP : INTRO;
                playback = new Playback(resource, volume);
            }

            long held = guideStarted ? updateHold(client) : 0L;
            if (held >= HOLD_TO_SKIP_NANOS) {
                LOGGER.info("Dash startup guide skipped by holding E");
                finish(client);
                return false;
            }

            if (renderFrame(client, graphics)) {
                if (held > 0) {
                    drawSkipProgress(graphics, held);
                }
                return true;
            }

        } catch (Exception | LinkageError exception) {
            LOGGER.warn("Dash startup playback failed", exception);
        }

        finish(client);
        return false;
    }

    public static boolean isPlayingStartup() {
        return playback != null;
    }

    private static long updateHold(Minecraft client) {
        if (!client.isWindowActive() || !InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_E)) {
            holdStart = Long.MIN_VALUE;
            return 0L;
        }

        long now = System.nanoTime();
        if (holdStart == Long.MIN_VALUE) {
            holdStart = now;
        }
        return now - holdStart;
    }

    private static void drawSkipProgress(GuiGraphics graphics, long held) {
        int width = Math.min(180, graphics.guiWidth() - 24);
        if (width <= 0) {
            return;
        }

        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 12;
        int filled = (int) (width * held / HOLD_TO_SKIP_NANOS);
        graphics.nextStratum();
        graphics.fill(x, y, x + width, y + 3, 0xBA152438);
        graphics.fill(x, y, x + filled, y + 3, 0xFF84D7FF);
    }

    private static boolean renderFrame(Minecraft client, GuiGraphics graphics) {
        if (playback.failure != null) {
            LOGGER.warn("Dash startup could not decode {}", playback.resource, playback.failure);
            return false;
        }

        long now = System.nanoTime();
        if (playback.texture == null) {
            if (playback.frames.size() >= PREBUFFERED_FRAMES || playback.decoded) {
                Frame first = playback.frames.poll();
                if (first == null) {
                    return false;
                }

                uploadFrame(client, first.image());
                playback.startAudio();
                playback.videoStart = System.nanoTime() - first.timestamp();
                LOGGER.info("Dash startup intro started from bundled {} ({}x{})",
                        playback.resource, first.image().getWidth(), first.image().getHeight());
            } else if (now - playback.preparingSince >= START_TIMEOUT_NANOS) {
                LOGGER.warn("Dash startup took too long to prepare {}", playback.resource);
                return false;
            }
        } else {
            long elapsed = now - playback.videoStart;
            if (elapsed >= playback.duration) {
                LOGGER.info("Dash startup finished {} after displaying {} video frames",
                        playback.resource, playback.displayedFrames);
                return false;
            }

            Frame next = playback.frames.peek();
            Frame due = null;
            while (next != null && next.timestamp() <= elapsed) {
                due = playback.frames.poll();
                next = playback.frames.peek();
            }
            if (due != null) {
                boolean enteringGuide = !guideStarted && STARTUP.equals(playback.resource)
                        && due.timestamp() >= GUIDE_START_NANOS;
                if (enteringGuide && !markGuideSeen()) {
                    return false;
                }
                uploadFrame(client, due.image());
                if (enteringGuide) {
                    guideStarted = true;
                    LOGGER.info("Dash startup intro finished");
                    LOGGER.info("Dash startup guide started");
                }
            }
        }

        drawCurrentFrame(graphics);
        return true;
    }

    private static Path guideMarker() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-client").resolve("guide-seen");
    }

    private static boolean markGuideSeen() {
        Path marker = guideMarker();
        try {
            Files.createDirectories(marker.getParent());
            Files.createFile(marker);
            return true;
        } catch (FileAlreadyExistsException exception) {
            return false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save that the Dash startup guide was shown", exception);
            return false;
        }
    }

    private static void uploadFrame(Minecraft client, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (playback.texture == null || width != playback.width || height != playback.height) {
            if (playback.texture != null) {
                client.getTextureManager().release(TEXTURE);
            }
            playback.width = width;
            playback.height = height;
            playback.texture = new DynamicTexture("dash_intro", width, height, false);
            client.getTextureManager().register(TEXTURE, playback.texture);
        }

        // AWTUtil supplies TYPE_3BYTE_BGR. NativeImage stores ABGR ints in native byte order.
        byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        var pixels = MemoryUtil.memIntBuffer(playback.texture.getPixels().getPointer(), width * height);
        for (int i = 0; i < bgr.length; i += 3) {
            int blue = bgr[i] & 0xFF;
            int green = bgr[i + 1] & 0xFF;
            int red = bgr[i + 2] & 0xFF;
            pixels.put(0xFF000000 | blue << 16 | green << 8 | red);
        }
        playback.texture.upload();
        playback.displayedFrames++;
    }

    private static void drawCurrentFrame(GuiGraphics graphics) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        graphics.nextStratum();
        graphics.fill(0, 0, width, height, 0xFF000000);
        if (playback.texture == null || width <= 0 || height <= 0) {
            return;
        }

        double scale = Math.min((double) width / playback.width, (double) height / playback.height);
        int drawWidth = (int) Math.round(playback.width * scale);
        int drawHeight = (int) Math.round(playback.height * scale);
        int x = (width - drawWidth) / 2;
        int y = (height - drawHeight) / 2;
        graphics.nextStratum();
        graphics.blit(TEXTURE, x, y, x + drawWidth, y + drawHeight, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    public static void finish(Minecraft client) {
        finished = true;
        Playback previous = playback;
        playback = null;
        guideStarted = false;
        holdStart = Long.MIN_VALUE;
        if (previous == null) return;
        try {
            previous.stop();
        } finally {
            if (previous.texture != null) {
                previous.texture = null;
                client.getTextureManager().release(TEXTURE);
            }
        }
    }

    private record Frame(long timestamp, BufferedImage image) {
    }

    private static final class Playback {
        private final String resource;
        private final long preparingSince = System.nanoTime();
        private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(PREBUFFERED_FRAMES);
        private final Thread worker;
        private volatile long duration;
        private volatile boolean decoded;
        private volatile Throwable failure;

        private DynamicTexture texture;
        private long videoStart;
        private int width;
        private int height;
        private int displayedFrames;
        private Clip audio;
        private volatile boolean stopped;

        private Playback(String resource, float volume) {
            this.resource = resource;
            worker = new Thread(() -> decode(volume), "Dash startup decoder (" + resource + ")");
            worker.setDaemon(true);
            worker.start();
        }

        private void decode(float volume) {
            try (var input = DashIntroVideo.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Bundled " + resource + " was missing");
                }

                byte[] bytes = input.readAllBytes();
                if (volume > 0.0F && !Thread.currentThread().isInterrupted()) {
                    loadAudio(bytes, volume);
                }
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                try (var channel = ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes))) {
                    FrameGrab decoder = FrameGrab.createFrameGrab(channel);
                    duration = toNanos(decoder.getVideoTrack().getMeta().getTotalDuration());
                    PriorityQueue<Frame> reorder = new PriorityQueue<>(Comparator.comparingLong(Frame::timestamp));
                    // H.264 B-frames can arrive out of presentation order.
                    for (var frame = decoder.getNativeFrameWithMetadata();
                         frame != null && !Thread.currentThread().isInterrupted();
                         frame = decoder.getNativeFrameWithMetadata()) {
                        BufferedImage image = AWTUtil.toBufferedImage(frame.getPicture(), frame.getOrientation());
                        reorder.add(new Frame(toNanos(frame.getTimestamp()), image));
                        if (reorder.size() > REORDER_LOOKAHEAD) {
                            frames.put(reorder.remove());
                        }
                    }
                    while (!reorder.isEmpty()) {
                        frames.put(reorder.remove());
                    }
                    decoded = true;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception | LinkageError exception) {
                failure = exception;
            } finally {
                if (stopped) frames.clear();
            }
        }

        private void loadAudio(byte[] bytes, float volume) {
            Clip clip = null;
            try (var channel = ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes))) {
                var tracks = MP4Demuxer.createMP4Demuxer(channel).getAudioTracks();
                if (tracks.isEmpty()) {
                    return;
                }

                var track = tracks.getFirst();
                AACDecoder decoder = new AACDecoder(track.getMeta().getCodecPrivate());
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                AudioFormat format = null;
                for (var packet = track.nextFrame();
                     packet != null && !Thread.currentThread().isInterrupted();
                     packet = track.nextFrame()) {
                    var sample = decoder.decodeFrame(packet.getData(), null);
                    if (format == null) {
                        var source = sample.getFormat();
                        format = new AudioFormat(source.getSampleRate(), source.getSampleSizeInBits(),
                                source.getChannels(), source.isSigned(), source.isBigEndian());
                    }
                    ByteBuffer data = sample.getData();
                    byte[] decodedSamples = new byte[data.remaining()];
                    data.get(decodedSamples);
                    pcm.writeBytes(decodedSamples);
                }
                if (format == null || Thread.currentThread().isInterrupted()) {
                    return;
                }

                clip = AudioSystem.getClip();
                byte[] samples = pcm.toByteArray();
                clip.open(format, samples, 0, samples.length);
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    gain.setValue(Math.max(gain.getMinimum(), (float) (20.0 * Math.log10(volume))));
                }
                synchronized (this) {
                    if (!stopped) {
                        audio = clip;
                        clip = null;
                    }
                }
            } catch (Exception | LinkageError exception) {
                LOGGER.warn("Dash startup audio for {} is unavailable: {}", resource, exception.getMessage());
            } finally {
                if (clip != null) {
                    clip.close();
                }
            }
        }

        private synchronized void startAudio() {
            if (audio != null) {
                audio.start();
            }
        }

        private synchronized void stop() {
            if (stopped) return;
            stopped = true;
            worker.interrupt();
            frames.clear();
            if (audio != null) {
                Clip clip = audio;
                audio = null;
                // An audio driver can block while closing its line.
                Thread.ofVirtual().start(() -> {
                    try {
                        clip.close();
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.warn("Could not close Dash startup audio", failure);
                    }
                });
            }
        }

        private static long toNanos(double seconds) {
            return Math.round(seconds * 1_000_000_000L);
        }
    }
}
