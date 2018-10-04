package server;

import util.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class ProxyServer extends Thread {
    private static final Logger logger = Logger.getLogger(ProxyServer.class.getName());
    private static final int BUF_SIZE = 1024;
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Object closeLock = new Object();
    private volatile boolean closed;
    private InetSocketAddress address;
    private List<String> blockPatterns;
    private Selector selector;
    private ServerSocketChannel channel;
    private ByteBuffer buffer;

    public ProxyServer(Config config) {
        this.address = new InetSocketAddress(config.getPort());
        this.blockPatterns = config.getBlockPatterns();
        this.buffer = ByteBuffer.allocate(BUF_SIZE);
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
                        case SelectionKey.OP_ACCEPT:
                            SocketChannel socket = channel.accept();
                            logger.info("build connection from " + socket.getRemoteAddress());
                            socket.configureBlocking(false);
                            socket.socket().setKeepAlive(true);
                            socket.register(selector, SelectionKey.OP_READ);
                            break;
                        case SelectionKey.OP_READ:
                            handleRead((SocketChannel) key.channel());
                            break;
                        case SelectionKey.OP_WRITE:
//                            handleWrite();
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

    public void handleRead(SocketChannel socketChannel) throws IOException {
        buffer.clear();
        int len;
        do {
            len = socketChannel.read(buffer);
            if (len == 0) break;
            if (len == -1) {
                logger.info("disconnect with " + socketChannel.getRemoteAddress());
                socketChannel.close();
            } else {
                buffer.flip();
                System.out.println(new String(buffer.array(), 0, len));
            }
        } while (len > 0);
    }

    public void handleWrite(SocketChannel socketChannel) {

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
