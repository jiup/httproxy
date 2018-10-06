package server;

import util.Config;

import java.util.logging.Logger;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        try {
            if (args.length != 1) {
                logger.info("usage: ProxyServer <config_file>");
                return;
            }

            new ProxyServer(new Config(args[0])).start();

        } catch (Exception e) {
            if (e.getMessage() != null) {
                logger.severe(e.getMessage());
            } else {
                e.printStackTrace();
            }
        }
    }
}
