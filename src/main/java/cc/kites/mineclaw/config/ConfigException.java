package cc.kites.mineclaw.config;

/** Checked failure raised when config.yml cannot be parsed or validated. */
public final class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
