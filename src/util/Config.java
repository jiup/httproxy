package util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * @author Jiupeng Zhang
 * @since 10/03/2018
 */
public class Config {
    private int port = 8080;
    private List<String> blockPatterns = new ArrayList<>();

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

    public List<String> getBlockPatterns() {
        return blockPatterns;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Config.class.getSimpleName() + "[", "]")
                .add("port=" + port)
                .add("blockPatterns=" + blockPatterns)
                .toString();
    }

    private void load(String filepath) throws IOException {
        Path path = Paths.get(filepath);
        if (!Files.exists(path)) throw new RuntimeException("config file not found '" + path.toAbsolutePath() + "'");

        Files.lines(path).forEach(line -> {
            if ((line = line.trim()).length() == 0 || line.startsWith("#")) return;

            String[] rowData = line.split(" +");
            switch (rowData[0]) {
                case "port":
                    port = Integer.parseInt(rowData[1]);
                    break;
                case "block":
                    blockPatterns.add(rowData[1]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown attribute '" + rowData[0] + "'");
            }
        });
    }
}
