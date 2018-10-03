package util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

public class Config {
    private int port = 9999;
    private Set<String> blockList = new HashSet<>();

    public Config(String path) {
        if (path != null) {
            try {
                load(path);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    public int getPort() {
        return port;
    }

    public Set<String> getBlockList() {
        return blockList;
    }

    private void load(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) throw new RuntimeException("config file not found '" + file.getAbsolutePath() + "'");

        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        String line;
        while (reader.ready()) {
            line = reader.readLine();
            if (line.length() == 0 || line.startsWith("#")) continue;

            String[] input = line.trim().split(" +");

            switch (input[0]) {
                case "port":
                    port = Integer.parseInt(input[1]);
                    break;
                case "block":
                    blockList.add(input[1]);
                    break;
                default:
                    throw new IllegalArgumentException("unsupported command " + input[0]);
            }
        }
    }
}
