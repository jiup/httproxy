package server;

import util.Config;

public class Main {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("usage: ProxyServer <config_file>");
            System.exit(0);
        }
        
        Config config = new Config(args[0]);
        System.out.println(new ProxyServer(config));
    }
}
