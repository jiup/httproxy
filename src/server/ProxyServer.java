package server;

import util.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ProxyServer extends Thread {
    private static final Logger logger = Logger.getLogger(ProxyServer.class.getName());
    private static final Pattern EOH = Pattern.compile("\r\n\r\n|\n\n");
    private static final Pattern CRLF = Pattern.compile("\r\n|\n");
    private static final int DATA_BUF_SIZE = 1024;
    private static final int REQ_BUF_SIZE = 8192;

    private final Map<SocketChannel, SocketChannel> outboundMap;
    private final Map<SocketChannel, SocketChannel> inboundMap;
    private final Object closeLock = new Object();
    private volatile boolean closed;
    private InetSocketAddress address;
    private List<String> blockPatterns;
    private ServerSocketChannel channel;
    private Selector selector;
    private ByteBuffer reqBuf;
    private ByteBuffer dataBuf;
    private ByteBuffer writeBuf;

    public ProxyServer(Config config) {
        this.outboundMap = new ConcurrentHashMap<>();
        this.inboundMap = new ConcurrentHashMap<>();
        this.address = new InetSocketAddress(config.getPort());
        this.blockPatterns = config.getBlockPatterns();
        this.reqBuf = ByteBuffer.allocate(REQ_BUF_SIZE);
        this.dataBuf = ByteBuffer.allocate(DATA_BUF_SIZE);
        this.writeBuf = ByteBuffer.allocate(DATA_BUF_SIZE);
        logger.info("proxy server initialized.");
    }

    int i = 0;
    @Override
    public void run() {
        try {
            selector = Selector.open();
            channel = ServerSocketChannel.open();
            channel.socket().bind(address);
            channel.configureBlocking(false);
            channel.register(selector, channel.validOps());
            logger.info("listening at " + address + "...");
            while (!closed) {
                selector.select();
                System.out.println("select #" + i++);
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    switch (key.interestOps()) {
                        case SelectionKey.OP_CONNECT:
                            handleConnect((SocketChannel) key.channel());
                            break;
                        case SelectionKey.OP_ACCEPT:
                            handleAccept(channel.accept());
                            break;
                        case SelectionKey.OP_READ:
                            handleRead((SocketChannel) key.channel());
                            break;
                        case SelectionKey.OP_WRITE:
                            handleWrite((SocketChannel) key.channel());
                            break;
                    }
                }
            }
        } catch (IOException e) {
            logger.severe(e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() {
        synchronized (closeLock) {
            if (closed) return;
            closed = true;
            doClose();
        }
    }

    public void handleConnect(SocketChannel socketChannel) throws IOException {
        if (socketChannel.finishConnect()) {
            logger.info("successfully connected to " + socketChannel.getRemoteAddress());
            socketChannel.register(selector, SelectionKey.OP_READ);
            handleWrite(socketChannel); // forward reqBuf
        } else {
            logger.info("failure connected to " + socketChannel.getRemoteAddress());
            closePair(socketChannel);
        }
    }

    public void handleAccept(SocketChannel socketChannel) throws IOException {
        logger.info("accept connection from " + socketChannel.getRemoteAddress());
        socketChannel.configureBlocking(false);
        socketChannel.socket().setKeepAlive(true);
        socketChannel.register(selector, SelectionKey.OP_READ);
    }

    public void handleRead(SocketChannel socketChannel) throws IOException {
        boolean inbound = inboundMap.containsKey(socketChannel);
        boolean outbound = outboundMap.containsKey(socketChannel);

        if (!inbound && !outbound) {
            handleHandshake(socketChannel);
            return;
        }

        dataBuf.clear();
        int len;
        do {
            try {
                len = socketChannel.read(dataBuf);
                dataBuf.flip();
            } catch (IOException e) {
                logger.info("reset by peer " + socketChannel);
                closePair(socketChannel);
                return;
            }
            if (len == 0) break;
            if (len == -1) {
                logger.info("disconnect by peer " + socketChannel.getRemoteAddress());
                closePair(socketChannel);
            } else {
                SocketChannel peerSocketChannel = inbound ? inboundMap.get(socketChannel) : outboundMap.get(socketChannel);
                writeBuf.clear();
                writeBuf.put(dataBuf.array(), 0, len);
                writeBuf.flip();
                peerSocketChannel.register(selector, SelectionKey.OP_WRITE);
//                handleWrite(peerSocketChannel);
            }
        } while (len > 0);
    }

    public void handleHandshake(SocketChannel socketChannel) throws IOException {
        reqBuf.clear();
        int len = socketChannel.read(reqBuf);
        reqBuf.flip();
        if (len == 0) return;
        if (len == -1) {
            logger.info("disconnect by peer " + socketChannel.getRemoteAddress());
            socketChannel.close();
            return;
        }

        CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
        CharBuffer charBuffer = decoder.decode(reqBuf);
        if (!EOH.matcher(charBuffer).find()) {
            if (len == REQ_BUF_SIZE) {
                // TODO 413 Request entity too large > 8192
                logger.info("request entity too large from " + socketChannel.getRemoteAddress());
            } else {
                // TODO 400 bad request
                logger.info("bad request (no EOH) from " + socketChannel.getRemoteAddress());
            }
            return;
        }
        Optional<String> requestHeader = EOH.splitAsStream(charBuffer).findFirst();
        if (!requestHeader.isPresent()) {
            // TODO 400 bad request
            logger.info("bad request (zero length header) from " + socketChannel.getRemoteAddress());
            return;
        }

        String[] lines = CRLF.split(requestHeader.get());
        if (lines.length < 1) {
            // TODO 400 bad request
            logger.info("bad request (no request_line) from " + socketChannel.getRemoteAddress());
            return;
        }

        // TODO valid request
        String[] fields = lines[0].split(" +");
        String method = fields[0];
        String requestURI = fields[1];
        String httpVersion = fields[2];
        logger.info("good request! " + method + ", " + requestURI + ", " + httpVersion);
        for (int i = 1; i < lines.length; i++) {
            fields = lines[i].split(":", 2);

            String attribute = fields[0].trim().toLowerCase();
            String value = fields[1].trim();

            // TODO parse headers above
//            logger.info("HEADER_ATTR " + attribute + ": " + value);
        }

        // TODO parse host and port
        URL url = new URL(requestURI.matches("^\\w+?://.*") ? requestURI : "http://".concat(requestURI));
        SocketAddress remoteAddress = new InetSocketAddress(url.getHost(), url.getPort() > 0 ? url.getPort() : 80);

        // TODO check connection availability
        SocketChannel outboundSocketChannel = SocketChannel.open();
        outboundSocketChannel.configureBlocking(false);
        outboundSocketChannel.register(selector, SelectionKey.OP_CONNECT);
        outboundSocketChannel.connect(remoteAddress);
        outboundMap.put(socketChannel, outboundSocketChannel);
        inboundMap.put(outboundSocketChannel, socketChannel);

        // TODO remote forward or refuse
        writeBuf.clear();
        writeBuf.put(reqBuf.array(), 0, len);
        writeBuf.flip();
        // TODO TODO
    }

    public void handleWrite(SocketChannel socketChannel) throws IOException {
        long len = 0;
        do {
            try {
                len += socketChannel.write(writeBuf);
            } catch (IOException e) {
                logger.info("reset by peer " + socketChannel);
                closePair(socketChannel);
                return;
            }
        } while (writeBuf.remaining() > 0);
        // TODO

        if (inboundMap.containsKey(socketChannel)) {
            logger.info("forward " + inboundMap.get(socketChannel).getRemoteAddress() +
                    " ==> " + socketChannel.getRemoteAddress() + "[" + len + " bytes]");
        } else if (outboundMap.containsKey(socketChannel)) {
            logger.info("forward " + socketChannel.getRemoteAddress() +
                    " <== " + outboundMap.get(socketChannel).getRemoteAddress() + "[" + len + " bytes]");
        } else {
            logger.severe("!! unknown transfer for " + socketChannel.getRemoteAddress());
        }
    }

    private void closePair(SocketChannel socketChannel) {
        if (inboundMap.containsKey(socketChannel)) {
            SocketChannel inboundChannel = inboundMap.get(socketChannel);
            synchronized (inboundMap) {
                synchronized (outboundMap) {
                    inboundMap.remove(socketChannel);
                    outboundMap.remove(inboundChannel);
                }
            }
            try {
                inboundChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (outboundMap.containsKey(socketChannel)) {
            SocketChannel outboundChannel = outboundMap.get(socketChannel);
            synchronized (outboundMap) {
                synchronized (inboundMap) {
                    outboundMap.remove(socketChannel);
                    inboundMap.remove(outboundChannel);
                }
            }
            try {
                outboundChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            socketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doClose() {
        try {
            channel.close();
            selector.selectedKeys().clear();
            selector.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
