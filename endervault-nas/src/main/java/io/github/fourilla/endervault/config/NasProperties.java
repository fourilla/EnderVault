package io.github.fourilla.endervault.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nas")
public class NasProperties {

    @Valid
    private Storage storage = new Storage();

    @Valid
    private Admin admin = new Admin();

    @Valid
    private Notifications notifications = new Notifications();

    @Valid
    private Thumbnails thumbnails = new Thumbnails();

    @Valid
    private Browser browser = new Browser();

    @Valid
    private Trash trash = new Trash();

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Notifications getNotifications() {
        return notifications;
    }

    public void setNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    public Thumbnails getThumbnails() {
        return thumbnails;
    }

    public void setThumbnails(Thumbnails thumbnails) {
        this.thumbnails = thumbnails;
    }

    public Browser getBrowser() {
        return browser;
    }

    public void setBrowser(Browser browser) {
        this.browser = browser;
    }

    public Trash getTrash() {
        return trash;
    }

    public void setTrash(Trash trash) {
        this.trash = trash;
    }

    public static class Storage {
        @NotNull
        private Path root = Path.of("./storage");

        @NotBlank
        private String trashDirectory = ".trash";

        @NotBlank
        private String metadataDirectory = ".endervault";

        public Path getRoot() {
            return root;
        }

        public void setRoot(Path root) {
            this.root = root;
        }

        public String getTrashDirectory() {
            return trashDirectory;
        }

        public void setTrashDirectory(String trashDirectory) {
            this.trashDirectory = trashDirectory;
        }

        public String getMetadataDirectory() {
            return metadataDirectory;
        }

        public void setMetadataDirectory(String metadataDirectory) {
            this.metadataDirectory = metadataDirectory;
        }
    }

    public static class Admin {
        @NotBlank
        private String username = "admin";

        @NotBlank
        private String password = "{noop}change-me";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Thumbnails {
        private boolean videoEnabled = true;

        @NotBlank
        private String cacheDirectory = "thumbnails";

        private int generatorThreads = 1;

        public boolean isVideoEnabled() {
            return videoEnabled;
        }

        public void setVideoEnabled(boolean videoEnabled) {
            this.videoEnabled = videoEnabled;
        }

        public String getCacheDirectory() {
            return cacheDirectory;
        }

        public void setCacheDirectory(String cacheDirectory) {
            this.cacheDirectory = cacheDirectory;
        }

        public int getGeneratorThreads() {
            return generatorThreads;
        }

        public void setGeneratorThreads(int generatorThreads) {
            this.generatorThreads = generatorThreads;
        }
    }

    public static class Browser {
        @NotBlank
        private String defaultView = "table";

        @NotBlank
        private String defaultSort = "name";

        @NotBlank
        private String defaultDirection = "asc";

        @Min(1)
        @Max(1000)
        private int defaultPageSize = 200;

        public String getDefaultView() {
            return defaultView;
        }

        public void setDefaultView(String defaultView) {
            this.defaultView = defaultView;
        }

        public String getDefaultSort() {
            return defaultSort;
        }

        public void setDefaultSort(String defaultSort) {
            this.defaultSort = defaultSort;
        }

        public String getDefaultDirection() {
            return defaultDirection;
        }

        public void setDefaultDirection(String defaultDirection) {
            this.defaultDirection = defaultDirection;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }
    }

    public static class Trash {
        @Min(1)
        private int retentionDays = 30;

        private boolean cleanupOnStartup = true;

        @Min(60000)
        private long cleanupIntervalMs = 3600000L;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public boolean isCleanupOnStartup() {
            return cleanupOnStartup;
        }

        public void setCleanupOnStartup(boolean cleanupOnStartup) {
            this.cleanupOnStartup = cleanupOnStartup;
        }

        public long getCleanupIntervalMs() {
            return cleanupIntervalMs;
        }

        public void setCleanupIntervalMs(long cleanupIntervalMs) {
            this.cleanupIntervalMs = cleanupIntervalMs;
        }
    }

    public static class Notifications {
        @Valid
        private Telegram telegram = new Telegram();

        public Telegram getTelegram() {
            return telegram;
        }

        public void setTelegram(Telegram telegram) {
            this.telegram = telegram;
        }
    }

    public static class Telegram {
        private boolean enabled;
        private String botToken = "";
        private String chatId = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBotToken() {
            return botToken;
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken;
        }

        public String getChatId() {
            return chatId;
        }

        public void setChatId(String chatId) {
            this.chatId = chatId;
        }
    }
}
