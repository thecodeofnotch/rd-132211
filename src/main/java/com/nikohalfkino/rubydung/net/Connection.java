package com.nikohalfkino.rubydung.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * one TCP link: a reader thread that cuts the stream into packets and a writer thread that drains a queue, so
 * send() never blocks the game. packets reach the listener on the reader thread, each with its own buffer, so
 * the listener may keep the stream and read it later on another thread
 */
public final class Connection {

    public interface Listener {
        /** reader thread. may throw IOException to drop the link (bad packet) */
        void onPacket(Connection connection, int type, DataInputStream in) throws IOException;

        /** called once, from whichever thread closed the link */
        void onClosed(Connection connection, String reason);
    }

    private static final byte[] CLOSE_MARK = new byte[0];

    /** a client that lets this many packets pile up is not keeping up, it is dropped */
    private static final int MAX_QUEUED_FRAMES = 40_000;

    private final Socket socket;
    private final Listener listener;
    private final String label;
    private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String closeReason = "Connection closed";

    /** whatever the owner wants to hang on the link (the server keeps the player's session here) */
    public volatile Object attachment;

    // the biggest packet this link reads
    private final int maxFrameBytes;

    public Connection(Socket socket, Listener listener, String label) throws IOException {
        this(socket, listener, label, Protocol.MAX_FRAME_BYTES);
    }

    public Connection(Socket socket, Listener listener, String label, int maxFrameBytes) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.label = label;
        this.maxFrameBytes = maxFrameBytes;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Protocol.SOCKET_TIMEOUT_MS);
        } catch (IOException | RuntimeException e) {
            // the socket would otherwise stay open with nobody holding it
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public String getLabel() {
        return this.label;
    }

    /** the address on the other end ("" if it is not known), used for ip bans */
    public String remoteIp() {
        java.net.InetAddress address = this.socket.getInetAddress();
        return address == null ? "" : address.getHostAddress();
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    public int queuedFrames() {
        return this.outbox.size();
    }

    public void start() {
        Thread reader = new Thread(this::readLoop, "net-read-" + this.label);
        reader.setDaemon(true);
        reader.start();
        Thread writer = new Thread(this::writeLoop, "net-write-" + this.label);
        writer.setDaemon(true);
        writer.start();
    }

    /** builds a complete frame, so one frame can be handed to several connections */
    public static byte[] frame(int type, Protocol.Writer body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(0);
            out.writeByte(type);
            body.write(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] frame = bytes.toByteArray();
        int length = frame.length - 4;
        frame[0] = (byte) (length >>> 24);
        frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        return frame;
    }

    public void send(int type, Protocol.Writer body) {
        sendFrame(frame(type, body));
    }

    public void sendFrame(byte[] frame) {
        if (this.closed.get()) {
            return;
        }
        this.outbox.add(frame);
        if (this.outbox.size() > MAX_QUEUED_FRAMES) {
            close("Too slow to keep up with the server");
        }
    }

    /** closes once everything queued so far has been written (a kick message gets through) */
    public void closeAfterFlush(String reason) {
        this.closeReason = reason;
        this.outbox.add(CLOSE_MARK);
    }

    public void close(String reason) {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.closeReason = reason;
        try {
            this.socket.close();
        } catch (IOException ignored) {
        }
        this.outbox.clear();
        this.outbox.add(CLOSE_MARK);
        this.listener.onClosed(this, reason);
    }

    private void readLoop() {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream(), 1 << 16))) {
            while (!this.closed.get()) {
                int length = in.readInt();
                if (length < 1 || length > this.maxFrameBytes) {
                    throw new IOException("bad packet length " + length);
                }
                byte[] body = new byte[length];
                in.readFully(body);
                DataInputStream payload = new DataInputStream(new ByteArrayInputStream(body));
                int type = payload.readUnsignedByte();
                this.listener.onPacket(this, type, payload);
            }
        } catch (java.net.SocketTimeoutException e) {
            close("Timed out");
        } catch (IOException | RuntimeException e) {
            close(this.closed.get() ? this.closeReason : "Connection lost (" + e.getMessage() + ")");
        }
    }

    private void writeLoop() {
        try {
            OutputStream out = new BufferedOutputStream(this.socket.getOutputStream(), 1 << 16);
            while (true) {
                byte[] frame = this.outbox.take();
                if (frame == CLOSE_MARK) {
                    out.flush();
                    close(this.closeReason);
                    return;
                }
                out.write(frame);
                if (this.outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            close("Connection lost (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
