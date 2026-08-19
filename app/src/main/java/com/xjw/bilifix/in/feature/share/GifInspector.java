package com.xjw.bilifix.in.feature.share;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Identifies GIF sources without decoding or re-encoding their animation frames. */
final class GifInspector {
    private static final int MAX_FRAMES_TO_SCAN = 2;

    private GifInspector() {
    }

    /**
     * Reports whether the source addresses a GIF original. Hosts append transform suffixes such
     * as {@code .gif@100w_100h.webp}, so ".gif" only counts when it terminates a path segment.
     */
    static boolean isGifUrl(String source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        String path = source;
        int queryStart = path.indexOf('?');
        int fragmentStart = path.indexOf('#');
        if (fragmentStart >= 0 && (queryStart < 0 || fragmentStart < queryStart)) {
            queryStart = fragmentStart;
        }
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf(".gif");
        if (marker < 0) {
            return false;
        }
        int afterMarker = marker + ".gif".length();
        return afterMarker == lower.length() || lower.charAt(afterMarker) == '@';
    }

    static boolean isGif(File file) {
        if (file == null || !file.isFile() || file.length() < 6L) {
            return false;
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[6];
            readFully(input, header);
            return isGifHeader(header);
        } catch (IOException ignored) {
            return false;
        }
    }

    static Inspection inspect(File file) {
        if (file == null || !file.isFile() || file.length() < 13L) {
            return Inspection.NOT_GIF;
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] header = new byte[6];
            readFully(input, header);
            if (!isGifHeader(header)) {
                return Inspection.NOT_GIF;
            }

            try {
                byte[] logicalScreenDescriptor = new byte[7];
                readFully(input, logicalScreenDescriptor);
                if ((logicalScreenDescriptor[4] & 0x80) != 0) {
                    skipFully(input, colorTableBytes(logicalScreenDescriptor[4]));
                }

                int frames = 0;
                while (true) {
                    int marker = input.read();
                    if (marker < 0 || marker == 0x3B) {
                        return new Inspection(true, frames);
                    }
                    if (marker == 0x21) {
                        requireByte(input);
                        skipSubBlocks(input);
                        continue;
                    }
                    if (marker != 0x2C) {
                        return new Inspection(true, -1);
                    }

                    byte[] imageDescriptor = new byte[9];
                    readFully(input, imageDescriptor);
                    if ((imageDescriptor[8] & 0x80) != 0) {
                        skipFully(input, colorTableBytes(imageDescriptor[8]));
                    }
                    requireByte(input);
                    skipSubBlocks(input);
                    frames++;
                    if (frames >= MAX_FRAMES_TO_SCAN) {
                        return new Inspection(true, frames);
                    }
                }
            } catch (IOException malformedGif) {
                // A valid GIF signature is enough to preserve and share the original bytes.
                return new Inspection(true, -1);
            }
        } catch (IOException ignored) {
            return Inspection.NOT_GIF;
        }
    }

    private static boolean isGifHeader(byte[] header) {
        return header.length >= 6
                && header[0] == 'G'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == '8'
                && (header[4] == '7' || header[4] == '9')
                && header[5] == 'a';
    }

    private static int colorTableBytes(byte packed) {
        return 3 * (1 << ((packed & 0x07) + 1));
    }

    private static void skipSubBlocks(InputStream input) throws IOException {
        while (true) {
            int length = requireByte(input);
            if (length == 0) {
                return;
            }
            skipFully(input, length);
        }
    }

    private static int requireByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("unexpected end of GIF");
        }
        return value;
    }

    private static void readFully(InputStream input, byte[] output) throws IOException {
        int offset = 0;
        while (offset < output.length) {
            int count = input.read(output, offset, output.length - offset);
            if (count < 0) {
                throw new IOException("unexpected end of GIF");
            }
            offset += count;
        }
    }

    private static void skipFully(InputStream input, int byteCount) throws IOException {
        int remaining = byteCount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= (int) skipped;
                continue;
            }
            requireByte(input);
            remaining--;
        }
    }

    static final class Inspection {
        static final Inspection NOT_GIF = new Inspection(false, 0);

        final boolean gif;
        final int frameCount;

        Inspection(boolean gif, int frameCount) {
            this.gif = gif;
            this.frameCount = frameCount;
        }

        boolean animated() {
            return frameCount >= 2;
        }

        String frameSummary() {
            if (frameCount < 0) {
                return "unknown";
            }
            return frameCount >= MAX_FRAMES_TO_SCAN ? "2+" : String.valueOf(frameCount);
        }
    }
}
