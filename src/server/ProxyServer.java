package server;

import util.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ProxyServer extends Thread {
    private static final Logger logger = Logger.getLogger(ProxyServer.class.getName());
    private static final CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
    private static final Pattern EOH = Pattern.compile("\r\n\r\n|\n\n");
    private static final Pattern CRLF = Pattern.compile("\r\n|\n");
    private static final String METHOD_GET = "GET";
    private static final String METHOD_QUIT = "QUIT";
    private static final String HTTP_VER_1_0 = "HTTP/1.0";
    private static final String HTTP_VER_1_1 = "HTTP/1.1";
    private static final int DATA_BUF_SIZE = 4096 * 1024; // 4MB
    private static final int REQ_BUF_SIZE = 8192; // 8KB

    static {
        logger.setLevel(Level.INFO);
    }

    private final Map<SocketChannel, SocketChannel> inboundMap;
    private final Map<SocketChannel, byte[]> requestBytes;
    private final Set<SocketChannel> futureClose;
    private final Object closeLock = new Object();
    private volatile boolean closed;
    private InetSocketAddress address;
    private List<String> blockPatterns;
    private ServerSocketChannel channel;
    private Selector selector;
    private ByteBuffer reqBuf;
    private ByteBuffer dataBuf;

    public ProxyServer(Config config) {
        this.inboundMap = new ConcurrentHashMap<>();
        this.requestBytes = new HashMap<>();
        this.futureClose = new HashSet<>();
        this.address = new InetSocketAddress(config.getPort());
        this.blockPatterns = config.getBlockPatterns();
        this.reqBuf = ByteBuffer.allocate(REQ_BUF_SIZE);
        this.dataBuf = ByteBuffer.allocate(DATA_BUF_SIZE);
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
                try {
                    selector.select();
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();
                        if (key.isValid()) {
                            switch (key.interestOps()) {
                                case SelectionKey.OP_ACCEPT:
                                    handleAccept(channel.accept());
                                    break;
                                case SelectionKey.OP_CONNECT:
                                    handleConnect((SocketChannel) key.channel());
                                    break;
                                case SelectionKey.OP_READ:
                                    handleRead((SocketChannel) key.channel());
                                    break;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (e.getMessage() != null) {
                        logger.severe(e.getMessage());
                    } else {
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException e) {
            if (e.getMessage() != null) {
                logger.severe(e.getMessage());
            } else {
                e.printStackTrace();
            }
        }
    }

    public void close() {
        synchronized (closeLock) {
            if (closed) return;
            closed = true;
            doClose();
        }
    }

    public void handleAccept(SocketChannel socketChannel) throws IOException {
        logger.config("accept connection from " + socketChannel);
        socketChannel.configureBlocking(false);
        socketChannel.socket().setKeepAlive(false);
        socketChannel.register(selector, SelectionKey.OP_READ);
    }

    public void handleConnect(SocketChannel socketChannel) throws IOException {
        if (socketChannel.finishConnect()) {
            logger.config("successfully connected to " + socketChannel);

            byte[] requestData = requestBytes.remove(socketChannel);
            logger.config((inboundMap.get(socketChannel) + " ==> " + socketChannel + " [" + requestData.length + " bytes]"));
            socketChannel.write(ByteBuffer.wrap(requestData));
            socketChannel.register(selector, SelectionKey.OP_READ);

        } else {
            logger.warning("failure connected to " + socketChannel);
            closePair(socketChannel);
        }
    }

    public void handleRead(SocketChannel socketChannel) {
        boolean inbound = inboundMap.containsKey(socketChannel);

        if (!inbound) {
            doRequest(socketChannel);
            return;
        }

        dataBuf.clear();
        int len;
        try {
            do {
                len = socketChannel.read(dataBuf);
                dataBuf.flip();
                if (len == 0) {
                    if (futureClose.remove(socketChannel)) {
                        logger.config("response ended, close connection pair: " + socketChannel);
                        closePair(socketChannel);
                    }
                    break;
                }
                if (len == -1) {
                    logger.config("disconnect by peer " + socketChannel);
                    closePair(socketChannel);
                } else {
                    SocketChannel inboundChannel = inboundMap.get(socketChannel);
                    CharBuffer charBuffer = decoder.decode(dataBuf);
                    if (len < REQ_BUF_SIZE && !futureClose.contains(socketChannel)) {
                        doResponse(charBuffer, socketChannel);
                    }

                    inboundChannel.write(ByteBuffer.wrap(Arrays.copyOf(dataBuf.array(), len)));
                    logger.config(inboundChannel + " <== " + socketChannel + " [" + len + " bytes]");
                }
            } while (len > 0);

        } catch (IOException e) {
            logger.warning("reset by peer " + socketChannel);
            closePair(socketChannel);
        }
    }

    public void doRequest(SocketChannel socketChannel) {
        try {
            reqBuf.clear();
            int len = socketChannel.read(reqBuf);
            reqBuf.flip();
            if (len == 0) return;
            if (len == -1) {
                logger.config("disconnect by peer " + socketChannel);
                closePair(socketChannel);
                return;
            }

            CharBuffer charBuffer = decoder.decode(reqBuf);
            if (!EOH.matcher(charBuffer).find()) {
                if (len == REQ_BUF_SIZE) {
                    logger.warning("request entity too large (>" + REQ_BUF_SIZE + ") for " + socketChannel);
                    socketChannel.write(ByteBuffer.wrap(getResponseBytes(413, "Request entity out of limit (" + REQ_BUF_SIZE + ")", true)));
                    socketChannel.close();
                } else {
                    logger.warning("bad request (no EOH) from " + socketChannel);
                    socketChannel.write(ByteBuffer.wrap(getResponseBytes(400, "Bad request (no EOH)", true)));
                    socketChannel.close();
                }
                return;
            }

            Optional<String> requestHeader = EOH.splitAsStream(charBuffer).findFirst();
            if (!requestHeader.isPresent()) {
                logger.warning("bad request (zero length header) from " + socketChannel);
                socketChannel.write(ByteBuffer.wrap(getResponseBytes(400, "Bad request (zero length header)", true)));
                socketChannel.close();
                return;
            }

            String[] lines = CRLF.split(requestHeader.get());
            if (lines.length < 1) {
                logger.warning("bad request (no request line) from " + socketChannel);
                socketChannel.write(ByteBuffer.wrap(getResponseBytes(400, "Bad request (no request line)", true)));
                socketChannel.close();
                return;
            }

            // check request headers
            String[] fields = lines[0].split(" +");
            String method = fields[0].toUpperCase();
            URL requestURI = new URL(fields[1].matches("^\\w+?://.*") ? fields[1] : "http://".concat(fields[1]));
            String httpVersion = fields[2].toUpperCase();
            if (METHOD_QUIT.equals(method)) {
                logger.warning("[quit] activated by " + socketChannel);
                socketChannel.close();
                close();
                return;
            }
            if (!METHOD_GET.equals(method)) {
                logger.warning("method '" + method + "' not supported for " + socketChannel);
                socketChannel.write(ByteBuffer.wrap(getResponseBytes(405, "HTTP_BAD_METHOD", false)));
                socketChannel.close();
                return;
            }

            for (String pattern : blockPatterns) {
                if (requestURI.getHost().matches(pattern)) {
                    logger.warning("request for '" + requestURI.getHost() + "' blocked by proxy " + socketChannel);
                    socketChannel.write(ByteBuffer.wrap(getResponseBytes(403, "Forbidden", true)));
                    socketChannel.close();
                    return;
                }
            }

            logger.config("request: " + method + " " + requestURI + " [" + httpVersion + "], from: " + socketChannel);
            if (httpVersion.equals(HTTP_VER_1_0)) {
                boolean keepAlive = false;
                for (int i = 1; i < lines.length; i++) {
                    fields = lines[i].split(":", 2);
                    String attribute = fields[0].trim().toLowerCase();
                    String value = fields[1].trim();

                    if (attribute.equals("connection") && !value.toLowerCase().equals("close")) {
                        keepAlive = true;
                    }
                }
                if (!keepAlive) {
                    socketChannel.close();
                }
            }

            // parse host and port
            SocketAddress remoteAddress = new InetSocketAddress(requestURI.getHost(),
                    requestURI.getPort() > 0 ? requestURI.getPort() : 80);

            // check connection availability
            SocketChannel outboundSocketChannel = SocketChannel.open();
            outboundSocketChannel.configureBlocking(false);
            outboundSocketChannel.socket().setKeepAlive(false);
            outboundSocketChannel.register(selector, SelectionKey.OP_CONNECT);
            outboundSocketChannel.connect(remoteAddress);
            inboundMap.put(outboundSocketChannel, socketChannel);
            requestBytes.put(outboundSocketChannel, Arrays.copyOf(reqBuf.array(), len));

        } catch (UnresolvedAddressException e) {
            logger.warning("request url unresolved " + socketChannel);
            closePair(socketChannel);

        } catch (IOException e) {
            if (e.getMessage() != null) {
                logger.warning(e.getMessage());
            } else {
                e.printStackTrace();
            }
        }
    }

    public void doResponse(CharBuffer charBuffer, SocketChannel socketChannel) {
        if (!EOH.matcher(charBuffer).find()) return;

        Optional<String> responseHeader = EOH.splitAsStream(charBuffer).findFirst();
        if (!responseHeader.isPresent()) return;

        String[] lines = CRLF.split(responseHeader.get());
        if (lines.length < 1) return; // header not contained

        String[] fields = lines[0].split(" +", 3);
        if (fields.length != 3) return; // non-first response

        String httpVersion = fields[0];
        String statusCode = fields[1];
        String reasonPhrase = fields[2];

        for (int i = 1; i < lines.length; i++) {
            fields = lines[i].split(":", 2);
            if (fields.length != 2) return; // not a header field

            String attribute = fields[0].trim().toLowerCase();
            String value = fields[1].trim();

            // handle close signal in response
            if (attribute.equals("connection") && value.toLowerCase().equals("close")) {
                futureClose.add(socketChannel);
                break;
            }
        }
        logger.config("response: " + statusCode + " " + reasonPhrase + " [" + httpVersion + "], from: " + socketChannel);
    }

    private byte[] getResponseBytes(int statusCode, String reasonPhrase, boolean withBody) {
        String responseStr = HTTP_VER_1_1 + " " + statusCode + " " + reasonPhrase + "\r\n" +
                "Content-Type: text/html\r\nConnection: close" + "\r\n\r\n" + (withBody ? "<html><head><title>" +
                statusCode + " " + reasonPhrase + "</title></head>" + "<body bgcolor='white'><center><h1>" +
                statusCode + " " + reasonPhrase + "</h1></center>" + "<hr><center>Proxy Server</center></body></html>" : "");
        return responseStr.getBytes(StandardCharsets.ISO_8859_1);
    }

    private void closePair(SocketChannel socketChannel) {
        synchronized (inboundMap) {
            try {
                socketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (inboundMap.containsKey(socketChannel)) {
                try {
                    inboundMap.remove(socketChannel).close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doClose() {
        try {
            selector.selectedKeys().clear();
            selector.close();
            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
