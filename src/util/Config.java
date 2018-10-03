package util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

public class Config {
    private int port = 9999;
    private Set<String> blockList = new HashSet<>();

    public Config(String path) {
        try {
            if (path != null) load(path);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public int getPort() {
        return port;
    }

    public Set<String> getBlockList() {
        return blockList;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Config.class.getSimpleName() + "[", "]")
                .add("port=" + port)
                .add("blockList=" + blockList)
                .toString();
    }

    private void load(String filepath) throws IOException {
        Path path = Paths.get(filepath);
        if (!Files.exists(path)) throw new RuntimeException("config file not found '" + path.toAbsolutePath() + "'");

        Files.lines(path).forEach(line -> {
            if ((line = line.trim()).length() == 0 || line.startsWith("#")) return;

            String[] rowData = line.split(" +");
            switch (rowData[0]) {
                case "port": port = Integer.parseInt(rowData[1]); break;
                case "block": blockList.add(rowData[1]); break;
                default: throw new IllegalArgumentException("unknown attribute '" + rowData[0] + "'");
            }
        });
    }
}
