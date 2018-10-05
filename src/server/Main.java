package server;

import util.Config;

import java.util.logging.Logger;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        try {
            if (args.length != 1) {
                logger.info("usage: ProxyServer <config_file>");
                System.exit(0);
            }

            Config config = new Config(args[0]);
            new ProxyServer(config).start();
        } catch (Exception e) {
            logger.severe(e.getMessage());
            e.printStackTrace();
        }
    }
}
