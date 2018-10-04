package server;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;

public class HttpRelay implements Runnable {
    private Map<SocketChannel, SocketChannel> outboundMap;
    private Map<SocketChannel, SocketChannel> inboundMap;
    private Selector selector;

    public HttpRelay() {

    }

    public void run() {

    }
}
