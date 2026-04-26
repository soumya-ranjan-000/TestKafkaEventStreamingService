package gammazon.automation.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Singleton ConfigManager that loads configuration from
 * resources/app.properties at startup.
 */
public class ConfigManager {

    private static final String PROPERTIES_FILE = "app.properties";
    private static volatile ConfigManager instance;

    private final Properties properties;

    // ── Private constructor ───────────────────────────────────────────────────

    private ConfigManager() {
        properties = new Properties();
        loadProperties();
    }

    // ── Singleton accessor ────────────────────────────────────────────────────

    /**
     * Returns the single instance of ConfigManager (thread-safe, lazy init).
     */
    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (ConfigManager.class) {
                if (instance == null) {
                    instance = new ConfigManager();
                }
            }
        }
        return instance;
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private void loadProperties() {
        try (InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream(PROPERTIES_FILE)) {

            if (inputStream == null) {
                throw new RuntimeException(
                        "Configuration file not found in classpath: " + PROPERTIES_FILE);
            }

            properties.load(inputStream);
            System.out.println("[ConfigManager] Loaded " + properties.size()
                    + " properties from " + PROPERTIES_FILE);

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to load configuration file: " + PROPERTIES_FILE, e);
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns the value for the given key, or {@code null} if not found.
     */
    public String get(String key) {
        return properties.getProperty(key);
    }

    /**
     * Returns the value for the given key, or {@code defaultValue} if not found.
     */
    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Sets a value for the given key (useful for dynamic test configurations).
     */
    public void set(String key, String value) {
        properties.setProperty(key, value);
    }

    /**
     * Returns the value parsed as an int.
     *
     * @throws NumberFormatException if the value cannot be parsed
     * @throws RuntimeException      if the key is not found
     */
    public int getInt(String key) {
        String value = getRequired(key);
        return Integer.parseInt(value.trim());
    }

    /**
     * Returns the value parsed as an int, or {@code defaultValue} if not found.
     */
    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        return Integer.parseInt(value.trim());
    }

    /**
     * Returns the value parsed as a boolean.
     * Accepts "true" / "false" (case-insensitive).
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Returns the value for the given key.
     *
     * @throws RuntimeException if the key is missing or blank
     */
    public String getRequired(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new RuntimeException(
                    "Missing required configuration property: " + key);
        }
        return value;
    }

    /**
     * Returns {@code true} if the key exists in the properties file.
     */
    public boolean contains(String key) {
        return properties.containsKey(key);
    }

    /**
     * Reloads the properties file (useful for hot-reload scenarios).
     */
    public synchronized void reload() {
        properties.clear();
        loadProperties();
        System.out.println("[ConfigManager] Configuration reloaded.");
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    /**
     * Prints all loaded properties to stdout (masks values with "password" in key).
     */
    public void printAll() {
        System.out.println("─── Configuration (" + PROPERTIES_FILE + ") ───");
        properties.forEach((k, v) -> {
            String display = k.toString().toLowerCase().contains("password")
                    ? "****" : v.toString();
            System.out.printf("  %-40s = %s%n", k, display);
        });
        System.out.println("────────────────────────────────────────────");
    }
}
