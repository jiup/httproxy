package server;

import util.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class ProxyServer implements Runnable {
    private static final Logger logger = Logger.getLogger(ProxyServer.class.getName());
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    private InetSocketAddress address;
    private Selector selector;
    private ServerSocketChannel channel;
    private ByteBuffer buffer;
//    private ServerSocket serverSocket;

    public ProxyServer(Config config) {
        address = new InetSocketAddress(config.getPort());
    }

    @Override
    public void run() {
        try {
            selector = Selector.open();
            logger.info("proxy server started.\nlistening at " + address + "...");
//            while (ssc.accept())
//            while (!serverSocket.isClosed()) {
//                Socket socket = serverSocket.accept();
//                executorService.execute(() -> {
//                    try {
//                        handle(socket);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                });
//            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void handle(SocketChannel socketChannel) {

    }
}
