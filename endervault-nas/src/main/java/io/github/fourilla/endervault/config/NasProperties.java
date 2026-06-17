package io.github.fourilla.endervault.config;

import io.github.fourilla.endervault.activity.ActivityTypeCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nas")
public class NasProperties {

    @Valid
    private Storage storage = new Storage();

    @Valid
    private Setup setup = new Setup();

    @Valid
    private Admin admin = new Admin();

    @Valid
    private Passkeys passkeys = new Passkeys();

    @Valid
    private Notifications notifications = new Notifications();

    @Valid
    private Thumbnails thumbnails = new Thumbnails();

    @Valid
    private Browser browser = new Browser();

    @Valid
    private Trash trash = new Trash();

    @Valid
    private RemoteDownload remoteDownload = new RemoteDownload();

    @Valid
    private Recent recent = new Recent();

    @Valid
    private FileTools fileTools = new FileTools();

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Setup getSetup() {
        return setup;
    }

    public void setSetup(Setup setup) {
        this.setup = setup;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Passkeys getPasskeys() {
        return passkeys;
    }

    public void setPasskeys(Passkeys passkeys) {
        this.passkeys = passkeys;
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

    public RemoteDownload getRemoteDownload() {
        return remoteDownload;
    }

    public void setRemoteDownload(RemoteDownload remoteDownload) {
        this.remoteDownload = remoteDownload;
    }

    public Recent getRecent() {
        return recent;
    }

    public void setRecent(Recent recent) {
        this.recent = recent;
    }

    public FileTools getFileTools() {
        return fileTools;
    }

    public void setFileTools(FileTools fileTools) {
        this.fileTools = fileTools;
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

    public static class Setup {
        private boolean accepted = false;

        public boolean isAccepted() {
            return accepted;
        }

        public void setAccepted(boolean accepted) {
            this.accepted = accepted;
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

    public static class Passkeys {
        private boolean enabled = true;

        @NotBlank
        private String rpId = "localhost";

        @NotBlank
        private String rpName = "EnderVault";

        @NotNull
        private List<String> allowedOrigins = List.of("http://localhost:8080");

        private boolean passwordLoginEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRpId() {
            return rpId;
        }

        public void setRpId(String rpId) {
            this.rpId = rpId;
        }

        public String getRpName() {
            return rpName;
        }

        public void setRpName(String rpName) {
            this.rpName = rpName;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public boolean isPasswordLoginEnabled() {
            return passwordLoginEnabled;
        }

        public void setPasswordLoginEnabled(boolean passwordLoginEnabled) {
            this.passwordLoginEnabled = passwordLoginEnabled;
        }
    }

    public static class Thumbnails {
        private boolean videoEnabled = true;

        private boolean comicEnabled = true;

        @NotBlank
        private String cacheDirectory = "thumbnails";

        private int generatorThreads = 1;

        public boolean isVideoEnabled() {
            return videoEnabled;
        }

        public void setVideoEnabled(boolean videoEnabled) {
            this.videoEnabled = videoEnabled;
        }

        public boolean isComicEnabled() {
            return comicEnabled;
        }

        public void setComicEnabled(boolean comicEnabled) {
            this.comicEnabled = comicEnabled;
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

    public static class RemoteDownload {
        private boolean enabled = true;

        private boolean directEnabled = true;

        private boolean extractorEnabled = false;

        private boolean blockPrivateNetworks = true;

        @NotNull
        private List<Integer> allowedPorts = List.of(80, 443);

        @Min(1)
        private int connectTimeoutSeconds = 10;

        @Min(1)
        private int responseTimeoutSeconds = 30;

        @Min(0)
        private int maxRedirects = 5;

        @Min(0)
        private long maxFileSizeBytes = 0L;

        @Min(1)
        @Max(8)
        private int workerThreads = 2;

        @Min(1)
        @Max(1000)
        private int historyLimit = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDirectEnabled() {
            return directEnabled;
        }

        public void setDirectEnabled(boolean directEnabled) {
            this.directEnabled = directEnabled;
        }

        public boolean isExtractorEnabled() {
            return extractorEnabled;
        }

        public void setExtractorEnabled(boolean extractorEnabled) {
            this.extractorEnabled = extractorEnabled;
        }

        public boolean isBlockPrivateNetworks() {
            return blockPrivateNetworks;
        }

        public void setBlockPrivateNetworks(boolean blockPrivateNetworks) {
            this.blockPrivateNetworks = blockPrivateNetworks;
        }

        public List<Integer> getAllowedPorts() {
            return allowedPorts;
        }

        public void setAllowedPorts(List<Integer> allowedPorts) {
            this.allowedPorts = allowedPorts;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getResponseTimeoutSeconds() {
            return responseTimeoutSeconds;
        }

        public void setResponseTimeoutSeconds(int responseTimeoutSeconds) {
            this.responseTimeoutSeconds = responseTimeoutSeconds;
        }

        public int getMaxRedirects() {
            return maxRedirects;
        }

        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }
    }

    public static class Recent {
        @Min(1)
        @Max(1000)
        private int maxItems = 200;

        private boolean recordDirectories = true;

        public int getMaxItems() {
            return maxItems;
        }

        public void setMaxItems(int maxItems) {
            this.maxItems = maxItems;
        }

        public boolean isRecordDirectories() {
            return recordDirectories;
        }

        public void setRecordDirectories(boolean recordDirectories) {
            this.recordDirectories = recordDirectories;
        }
    }

    public static class FileTools {
        @Min(1024)
        private long textAutoLoadMaxBytes = 1048576L;

        @Min(1024)
        private long textManualLoadMaxBytes = 20971520L;

        @Min(1)
        @Max(50000)
        private int comicMaxPages = 5000;

        @Min(1024)
        private long comicPageMaxBytes = 104857600L;

        @Min(1024)
        private long comicInfoMaxBytes = 65536L;

        public long getTextAutoLoadMaxBytes() {
            return textAutoLoadMaxBytes;
        }

        public void setTextAutoLoadMaxBytes(long textAutoLoadMaxBytes) {
            this.textAutoLoadMaxBytes = textAutoLoadMaxBytes;
        }

        public long getTextManualLoadMaxBytes() {
            return textManualLoadMaxBytes;
        }

        public void setTextManualLoadMaxBytes(long textManualLoadMaxBytes) {
            this.textManualLoadMaxBytes = textManualLoadMaxBytes;
        }

        public int getComicMaxPages() {
            return comicMaxPages;
        }

        public void setComicMaxPages(int comicMaxPages) {
            this.comicMaxPages = comicMaxPages;
        }

        public long getComicPageMaxBytes() {
            return comicPageMaxBytes;
        }

        public void setComicPageMaxBytes(long comicPageMaxBytes) {
            this.comicPageMaxBytes = comicPageMaxBytes;
        }

        public long getComicInfoMaxBytes() {
            return comicInfoMaxBytes;
        }

        public void setComicInfoMaxBytes(long comicInfoMaxBytes) {
            this.comicInfoMaxBytes = comicInfoMaxBytes;
        }

        @Deprecated
        public long getTextMaxBytes() {
            return textAutoLoadMaxBytes;
        }

        @Deprecated
        public void setTextMaxBytes(long textMaxBytes) {
            this.textAutoLoadMaxBytes = textMaxBytes;
            this.textManualLoadMaxBytes = Math.max(this.textManualLoadMaxBytes, textMaxBytes);
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

        private Map<String, Boolean> activity = defaultActivity();

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

        public Map<String, Boolean> getActivity() {
            return activity;
        }

        public void setActivity(Map<String, Boolean> activity) {
            Map<String, Boolean> merged = defaultActivity();
            if (activity != null) {
                activity.forEach((key, value) -> {
                    if (key != null && value != null) {
                        merged.put(key, value);
                    }
                });
            }
            this.activity = merged;
        }

        public boolean isActivityEnabled(String type) {
            String normalizedType = normalizeActivityKey(type);
            return activity.entrySet().stream()
                    .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .map(this::normalizeActivityKey)
                    .anyMatch(normalizedType::equals);
        }

        private static Map<String, Boolean> defaultActivity() {
            return ActivityTypeCatalog.defaultTelegramActivity();
        }

        private String normalizeActivityKey(String value) {
            return ActivityTypeCatalog.normalizeKey(value);
        }
    }
}
