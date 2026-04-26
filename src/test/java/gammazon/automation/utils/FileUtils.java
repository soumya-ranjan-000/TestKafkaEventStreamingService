package gammazon.automation.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class FileUtils {

    public static String readFileFromResources(String fileName) {
        try (InputStream is = FileUtils.class.getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IllegalArgumentException("File not found in resources: " + fileName);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + fileName, e);
        }
    }
}
