package server;

import util.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CRL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ProxyServer extends Thread {
    private static final Logger logger = Logger.getLogger(ProxyServer.class.getName());
    private static final Pattern EOH = Pattern.compile("\r\n\r\n|\n\n");
    private static final Pattern CRLF = Pattern.compile("\r\n|\n");
    private static final int DATA_BUF_SIZE = 1023;
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
        } else {
            logger.info("failure connected to " + socketChannel.getRemoteAddress());
            closeChannelPair(socketChannel);
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
            reqBuf.clear();
            int len = socketChannel.read(reqBuf);
            if (len == 0) return;
            if (len == -1) {
                logger.info("disconnect by peer " + socketChannel.getRemoteAddress());
                socketChannel.close();
                return;
            }
            reqBuf.flip();

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

            logger.info("good request!");
            String requestLine = lines[0];
            for (int i = 1; i < lines.length; i++) {
                String[] fields = lines[i].split(":", 2);

                String attribute = fields[0].trim().toLowerCase();
                String value = fields[1].trim();

                // TODO parse headers above
            }

            // TODO parse host and port
            String host = "www.google.com";
            int port = 80;

            // TODO intercept request
            boolean passed = true;

            // TODO check connection availability
            if (passed) {
                SocketChannel outboundSocketChannel = SocketChannel.open();
                outboundSocketChannel.configureBlocking(false);
                outboundSocketChannel.register(selector, SelectionKey.OP_CONNECT);
                outboundSocketChannel.connect(new InetSocketAddress(host, port));
                outboundMap.put(socketChannel, outboundSocketChannel);
                inboundMap.put(outboundSocketChannel, socketChannel);
            }

            // TODO remote forward or refuse
            return;
        }

        dataBuf.clear();
        int len;
        do {
            len = socketChannel.read(dataBuf);
            if (len == 0) break;
            if (len == -1) {
                logger.info("disconnect by peer " + socketChannel.getRemoteAddress());
                closeChannelPair(socketChannel);
            } else {
                dataBuf.flip();

                if (outbound) {
                    SocketChannel outboundSocketChannel = outboundMap.get(socketChannel);

                    // TODO auto-redirect forward
                    logger.info(socketChannel.getRemoteAddress() + " ==> " + outboundSocketChannel.getRemoteAddress());
                    String request = "GET  HTTP/1.1\n" +
                            "Host: www.google.com\n" +
                            "Cache-Control: no-cache";
                    outboundSocketChannel.write(ByteBuffer.wrap(request.getBytes())); // just for test

                } else {
                    SocketChannel inboundSocketChannel = inboundMap.get(socketChannel);

                    // TODO auto-redirect backward
                    logger.info(inboundSocketChannel.getRemoteAddress() + " <== " + socketChannel.getRemoteAddress());
                    System.out.println(new String(dataBuf.array(), 0, len)); // just for test
                }
            }
        } while (len > 0);
    }

    public void handleWrite(SocketChannel socketChannel) throws IOException {
        System.out.println("writing....");
        writeBuf.clear();

        writeBuf.put("hello".getBytes());
        writeBuf.putChar('\n');
        writeBuf.flip();

        long limit = writeBuf.remaining();
        long len = 0;
        do {
            len += socketChannel.write(writeBuf);
        } while (len < limit);
        writeBuf.rewind();
    }

    private void closeChannelPair(SocketChannel socketChannel) {
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
