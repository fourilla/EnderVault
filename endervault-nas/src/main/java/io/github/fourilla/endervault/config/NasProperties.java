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

    public static class Storage {
        @NotNull
        private Path root = Path.of("./storage");

        @NotBlank
        private String trashFolder = ".trash";

        @NotBlank
        private String metadataFolder = ".endervault";

        public Path getRoot() {
            return root;
        }

        public void setRoot(Path root) {
            this.root = root;
        }

        public String getTrashFolder() {
            return trashFolder;
        }

        public void setTrashFolder(String trashFolder) {
            this.trashFolder = trashFolder;
        }

        public String getMetadataFolder() {
            return metadataFolder;
        }

        public void setMetadataFolder(String metadataFolder) {
            this.metadataFolder = metadataFolder;
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
        private String cacheFolder = "thumbnails";

        private int generatorThreads = 1;

        public boolean isVideoEnabled() {
            return videoEnabled;
        }

        public void setVideoEnabled(boolean videoEnabled) {
            this.videoEnabled = videoEnabled;
        }

        public String getCacheFolder() {
            return cacheFolder;
        }

        public void setCacheFolder(String cacheFolder) {
            this.cacheFolder = cacheFolder;
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
