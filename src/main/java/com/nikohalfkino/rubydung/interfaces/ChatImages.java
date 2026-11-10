package com.nikohalfkino.rubydung.interfaces;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.lwjgl.opengl.GL11.*;

/**
 * pictures linked in the chat: downloaded and decoded on a worker thread, turned into a texture on the
 * thread that draws (get() does that, so only call it from there)
 *
 * http / https only, at most MAX_BYTES big, and anything wider or taller than MAX_SIDE is scaled down
 * once it is decoded. a picture that failed stays failed until it leaves the cache
 */
public final class ChatImages {

    public enum State { LOADING, DECODED, READY, FAILED }

    public static final class Entry {
        volatile State state = State.LOADING;
        // set by the worker before state becomes DECODED, dropped once uploaded
        volatile int[] pixels;
        volatile int width;
        volatile int height;
        // 0 until READY
        int texture = 0;

        public boolean isReady() {
            return this.state == State.READY;
        }

        public boolean isFailed() {
            return this.state == State.FAILED;
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }
    }

    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SIDE = 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int CACHE_SIZE = 64;

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "chat-image");
        t.setDaemon(true);
        return t;
    });

    // draw thread only
    private static final Map<String, Entry> CACHE = new LinkedHashMap<String, Entry>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            if (size() <= CACHE_SIZE) {
                return false;
            }
            release(eldest.getValue());
            return true;
        }
    };

    private ChatImages() {
    }

    /** the picture for url, starting the download the first time it is asked for */
    public static Entry get(String url) {
        // nothing is downloaded where the server does not allow embeds, a picture there is just "unavailable"
        if (!com.nikohalfkino.rubydung.net.Multiplayer.embedsAllowed()) {
            Entry blocked = new Entry();
            blocked.state = State.FAILED;
            return blocked;
        }

        Entry entry = CACHE.get(url);

        if (entry == null) {
            entry = new Entry();
            CACHE.put(url, entry);
            start(url, entry);
        } else if (entry.state == State.DECODED) {
            upload(entry);
        }

        return entry;
    }

    /** draws the rows v0..v1 (0..1, top to bottom) of a ready picture into the rectangle */
    public static void drawSlice(Entry entry, float x, float y, float width, float height, float v0, float v1) {
        if (!entry.isReady()) {
            return;
        }

        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, entry.texture);
        glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        glBegin(GL_QUADS);
        glTexCoord2f(0.0F, v0); glVertex2f(x, y);
        glTexCoord2f(1.0F, v0); glVertex2f(x + width, y);
        glTexCoord2f(1.0F, v1); glVertex2f(x + width, y + height);
        glTexCoord2f(0.0F, v1); glVertex2f(x, y + height);
        glEnd();

        glDisable(GL_TEXTURE_2D);
    }

    private static void release(Entry entry) {
        if (entry.texture != 0) {
            glDeleteTextures(entry.texture);
            entry.texture = 0;
        }
        // a worker still busy with it finishes into an entry nobody looks at any more
        entry.pixels = null;
        entry.state = State.FAILED;
    }

    private static void upload(Entry entry) {
        int[] pixels = entry.pixels;
        int width = entry.width;
        int height = entry.height;

        if (pixels == null || width <= 0 || height <= 0) {
            entry.state = State.FAILED;
            return;
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF));
            buffer.put((byte) ((pixel >> 8) & 0xFF));
            buffer.put((byte) (pixel & 0xFF));
            buffer.put((byte) ((pixel >> 24) & 0xFF));
        }
        buffer.flip();

        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        entry.texture = id;
        entry.pixels = null;
        entry.state = State.READY;
    }

    // worker side

    private static void start(String url, Entry entry) {
        WORKERS.execute(() -> {
            try {
                BufferedImage image = decode(download(url));
                if (image == null) {
                    entry.state = State.FAILED;
                    return;
                }

                image = limitSize(image);
                int w = image.getWidth();
                int h = image.getHeight();

                entry.width = w;
                entry.height = h;
                entry.pixels = image.getRGB(0, 0, w, h, null, 0, w);

                // released while it was downloading: nobody is waiting for it
                if (entry.state == State.LOADING) {
                    entry.state = State.DECODED;
                } else {
                    entry.pixels = null;
                }
            } catch (Throwable t) {
                entry.state = State.FAILED;
            }
        });
    }

    private static byte[] download(String address) throws IOException {
        URL url = new URL(address);

        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            String protocol = url.getProtocol();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IOException("not http(s)");
            }

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            // followed by hand below: HttpURLConnection won't follow http -> https
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (RubyDung chat)");
            connection.setRequestProperty("Accept", "image/*");

            try {
                int code = connection.getResponseCode();

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("redirect without a location");
                    }
                    url = new URL(url, location);
                    continue;
                }

                if (code != 200) {
                    throw new IOException("http " + code);
                }

                if (connection.getContentLengthLong() > MAX_BYTES) {
                    throw new IOException("too big");
                }

                try (InputStream in = connection.getInputStream()) {
                    return readLimited(in);
                }
            } finally {
                connection.disconnect();
            }
        }

        throw new IOException("too many redirects");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int read;

        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            if (out.size() > MAX_BYTES) {
                throw new IOException("too big");
            }
        }

        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private static BufferedImage limitSize(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        if (w <= MAX_SIDE && h <= MAX_SIDE) {
            return image;
        }

        double scale = MAX_SIDE / (double) Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, nw, nh, null);
        g.dispose();

        return scaled;
    }
}
