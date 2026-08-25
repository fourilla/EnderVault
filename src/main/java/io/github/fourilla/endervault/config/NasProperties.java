package io.github.fourilla.endervault.config;

import io.github.fourilla.endervault.activity.ActivityTypeCatalog;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
    private Security security = new Security();

    @Valid
    private Server server = new Server();

    @Valid
    private Outbound outbound = new Outbound();

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
    private StickyNotes stickyNotes = new StickyNotes();

    @Valid
    private Trash trash = new Trash();

    @Valid
    private RemoteDownload remoteDownload = new RemoteDownload();

    @Valid
    private Recent recent = new Recent();

    @Valid
    private Bookmarks bookmarks = new Bookmarks();

    @Valid
    private FileTools fileTools = new FileTools();

    @Valid
    private Share share = new Share();

    @Valid
    private FileRequest fileRequest = new FileRequest();

    @Valid
    private PendingFileDecisions pendingFileDecisions = new PendingFileDecisions();

    @Valid
    private Upload upload = new Upload();

    @Valid
    private TemporaryArtifacts temporaryArtifacts = new TemporaryArtifacts();

    @Valid
    private ActivityLog activityLog = new ActivityLog();

    @Valid
    private Tasks tasks = new Tasks();

    @Valid
    private MetadataInspector metadataInspector = new MetadataInspector();

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

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public void setOutbound(Outbound outbound) {
        this.outbound = outbound;
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

    public StickyNotes getStickyNotes() {
        return stickyNotes;
    }

    public void setStickyNotes(StickyNotes stickyNotes) {
        this.stickyNotes = stickyNotes;
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

    public Bookmarks getBookmarks() {
        return bookmarks;
    }

    public void setBookmarks(Bookmarks bookmarks) {
        this.bookmarks = bookmarks;
    }

    public FileTools getFileTools() {
        return fileTools;
    }

    public void setFileTools(FileTools fileTools) {
        this.fileTools = fileTools;
    }

    public Share getShare() {
        return share;
    }

    public void setShare(Share share) {
        this.share = share;
    }

    public FileRequest getFileRequest() {
        return fileRequest;
    }

    public void setFileRequest(FileRequest fileRequest) {
        this.fileRequest = fileRequest;
    }

    public PendingFileDecisions getPendingFileDecisions() {
        return pendingFileDecisions;
    }

    public void setPendingFileDecisions(PendingFileDecisions pendingFileDecisions) {
        this.pendingFileDecisions = pendingFileDecisions;
    }

    public Upload getUpload() {
        return upload;
    }

    public void setUpload(Upload upload) {
        this.upload = upload;
    }

    public TemporaryArtifacts getTemporaryArtifacts() {
        return temporaryArtifacts;
    }

    public void setTemporaryArtifacts(TemporaryArtifacts temporaryArtifacts) {
        this.temporaryArtifacts = temporaryArtifacts;
    }

    public ActivityLog getActivityLog() {
        return activityLog;
    }

    public void setActivityLog(ActivityLog activityLog) {
        this.activityLog = activityLog;
    }

    public Tasks getTasks() {
        return tasks;
    }

    public void setTasks(Tasks tasks) {
        this.tasks = tasks;
    }

    public MetadataInspector getMetadataInspector() {
        return metadataInspector;
    }

    public void setMetadataInspector(MetadataInspector metadataInspector) {
        this.metadataInspector = metadataInspector;
    }

    public static class Storage {
        @NotNull
        private Path root = Path.of("./storage");

        @NotBlank
        private String trashDirectory = ".trash";

        @NotBlank
        private String metadataDirectory = ".endervault";

        @NotBlank
        private String defaultConflictPolicy = "cancel";

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

        public String getDefaultConflictPolicy() {
            return defaultConflictPolicy;
        }

        public void setDefaultConflictPolicy(String defaultConflictPolicy) {
            this.defaultConflictPolicy = defaultConflictPolicy;
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

    public static class Security {
        @NotNull
        private List<String> trustedProxies = List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

        @Min(0)
        @Max(1000)
        private int maxConcurrentSessions = 2;

        @Min(0)
        @Max(525600)
        private int sessionIdleTimeoutMinutes = 30;

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(List<String> trustedProxies) {
            this.trustedProxies = trustedProxies == null ? List.of() : trustedProxies;
        }

        public int getMaxConcurrentSessions() {
            return maxConcurrentSessions;
        }

        public void setMaxConcurrentSessions(int maxConcurrentSessions) {
            this.maxConcurrentSessions = maxConcurrentSessions;
        }

        public int getSessionIdleTimeoutMinutes() {
            return sessionIdleTimeoutMinutes;
        }

        public void setSessionIdleTimeoutMinutes(int sessionIdleTimeoutMinutes) {
            this.sessionIdleTimeoutMinutes = sessionIdleTimeoutMinutes;
        }
    }

    public static class Server {
        private String publicBaseUrl = "";

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        }
    }

    public static class Outbound {
        @NotNull
        private NetworkRoute initialRoute = NetworkRoute.DIRECT;

        @Valid
        private Vpn vpn = new Vpn();

        public NetworkRoute getInitialRoute() {
            return initialRoute;
        }

        public void setInitialRoute(NetworkRoute initialRoute) {
            this.initialRoute = initialRoute == null ? NetworkRoute.DIRECT : initialRoute;
        }

        public Vpn getVpn() {
            return vpn;
        }

        public void setVpn(Vpn vpn) {
            this.vpn = vpn;
        }
    }

    public static class Vpn {
        private boolean enabled;
        private String proxyHost = "";

        @Min(1)
        @Max(65535)
        private int proxyPort = 8888;

        @Min(100)
        @Max(60000)
        private int healthConnectTimeoutMs = 1500;

        private String tunnelHealthUrl = "";

        @Min(100)
        @Max(60000)
        private int healthRequestTimeoutMs = 3000;

        @Min(1000)
        private long healthCheckIntervalMs = 30000L;

        private String controlUrl = "";
        private String controlApiKeyFile = "";
        private String profileName = "";

        @Min(100)
        @Max(60000)
        private int controlRequestTimeoutMs = 3000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProxyHost() {
            return proxyHost;
        }

        public void setProxyHost(String proxyHost) {
            this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
        }

        public int getProxyPort() {
            return proxyPort;
        }

        public void setProxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
        }

        public int getHealthConnectTimeoutMs() {
            return healthConnectTimeoutMs;
        }

        public void setHealthConnectTimeoutMs(int healthConnectTimeoutMs) {
            this.healthConnectTimeoutMs = healthConnectTimeoutMs;
        }

        public String getTunnelHealthUrl() {
            return tunnelHealthUrl;
        }

        public void setTunnelHealthUrl(String tunnelHealthUrl) {
            this.tunnelHealthUrl = tunnelHealthUrl == null ? "" : tunnelHealthUrl.trim();
        }

        public int getHealthRequestTimeoutMs() {
            return healthRequestTimeoutMs;
        }

        public void setHealthRequestTimeoutMs(int healthRequestTimeoutMs) {
            this.healthRequestTimeoutMs = healthRequestTimeoutMs;
        }

        public long getHealthCheckIntervalMs() {
            return healthCheckIntervalMs;
        }

        public void setHealthCheckIntervalMs(long healthCheckIntervalMs) {
            this.healthCheckIntervalMs = healthCheckIntervalMs;
        }

        public String getControlUrl() {
            return controlUrl;
        }

        public void setControlUrl(String controlUrl) {
            this.controlUrl = controlUrl == null ? "" : controlUrl.trim();
        }

        public String getControlApiKeyFile() {
            return controlApiKeyFile;
        }

        public void setControlApiKeyFile(String controlApiKeyFile) {
            this.controlApiKeyFile = controlApiKeyFile == null ? "" : controlApiKeyFile.trim();
        }

        public String getProfileName() {
            return profileName;
        }

        public void setProfileName(String profileName) {
            this.profileName = profileName == null ? "" : profileName.trim();
        }

        public int getControlRequestTimeoutMs() {
            return controlRequestTimeoutMs;
        }

        public void setControlRequestTimeoutMs(int controlRequestTimeoutMs) {
            this.controlRequestTimeoutMs = controlRequestTimeoutMs;
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

        private boolean pdfEnabled = true;

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

        public boolean isPdfEnabled() {
            return pdfEnabled;
        }

        public void setPdfEnabled(boolean pdfEnabled) {
            this.pdfEnabled = pdfEnabled;
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

    public static class StickyNotes {
        public static final String DEFAULT_BACKGROUND_COLOR = "#1B3033";
        public static final String DEFAULT_BORDER_COLOR = "#4E8F8A";
        public static final String DEFAULT_TEXT_COLOR = "#EAF6F4";

        @NotBlank
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
        private String backgroundColor = DEFAULT_BACKGROUND_COLOR;

        @NotBlank
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
        private String borderColor = DEFAULT_BORDER_COLOR;

        @NotBlank
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
        private String textColor = DEFAULT_TEXT_COLOR;

        public String getBackgroundColor() {
            return backgroundColor;
        }

        public void setBackgroundColor(String backgroundColor) {
            this.backgroundColor = backgroundColor;
        }

        public String getBorderColor() {
            return borderColor;
        }

        public void setBorderColor(String borderColor) {
            this.borderColor = borderColor;
        }

        public String getTextColor() {
            return textColor;
        }

        public void setTextColor(String textColor) {
            this.textColor = textColor;
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
        private boolean enabled = false;

        private boolean directEnabled = true;

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

        @Min(0)
        @Max(5)
        private int maxRetries = 2;

        private boolean skipInspectByDefault = false;

        private String defaultTargetDirectory = "";

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

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isSkipInspectByDefault() {
            return skipInspectByDefault;
        }

        public void setSkipInspectByDefault(boolean skipInspectByDefault) {
            this.skipInspectByDefault = skipInspectByDefault;
        }

        public String getDefaultTargetDirectory() {
            return defaultTargetDirectory;
        }

        public void setDefaultTargetDirectory(String defaultTargetDirectory) {
            this.defaultTargetDirectory = defaultTargetDirectory == null ? "" : defaultTargetDirectory;
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

    public static class Bookmarks {
        @NotBlank
        private String linkClickAction = "open";

        private boolean metadataFetchEnabled = false;

        private boolean blockPrivateNetworks = true;

        @NotNull
        private List<Integer> allowedPorts = List.of(80, 443);

        @Min(1)
        private int connectTimeoutSeconds = 5;

        @Min(1)
        private int responseTimeoutSeconds = 8;

        @Min(0)
        private int maxRedirects = 3;

        @Min(1024)
        private int htmlMaxBytes = 524288;

        @Min(1024)
        private int faviconMaxBytes = 262144;

        @NotBlank
        private String faviconCacheDirectory = "bookmark-favicons";

        public String getLinkClickAction() {
            return linkClickAction;
        }

        public void setLinkClickAction(String linkClickAction) {
            this.linkClickAction = "detail".equalsIgnoreCase(linkClickAction == null ? "" : linkClickAction.trim())
                    ? "detail"
                    : "open";
        }

        public boolean isMetadataFetchEnabled() {
            return metadataFetchEnabled;
        }

        public void setMetadataFetchEnabled(boolean metadataFetchEnabled) {
            this.metadataFetchEnabled = metadataFetchEnabled;
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
            this.allowedPorts = allowedPorts == null ? List.of(80, 443) : allowedPorts;
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

        public int getHtmlMaxBytes() {
            return htmlMaxBytes;
        }

        public void setHtmlMaxBytes(int htmlMaxBytes) {
            this.htmlMaxBytes = htmlMaxBytes;
        }

        public int getFaviconMaxBytes() {
            return faviconMaxBytes;
        }

        public void setFaviconMaxBytes(int faviconMaxBytes) {
            this.faviconMaxBytes = faviconMaxBytes;
        }

        public String getFaviconCacheDirectory() {
            return faviconCacheDirectory;
        }

        public void setFaviconCacheDirectory(String faviconCacheDirectory) {
            this.faviconCacheDirectory = faviconCacheDirectory;
        }
    }

    public static class FileTools {
        @Min(1024)
        private long textAutoLoadMaxBytes = 1048576L;

        @Min(1024)
        private long textManualLoadMaxBytes = 20971520L;

        @Min(1)
        private long textDraftRetentionHours = 168L;

        @Min(60000)
        private long textDraftCleanupIntervalMs = 3600000L;

        @Min(30)
        private long textDraftLeaseSeconds = 300L;

        @Min(1)
        @Max(50000)
        private int comicMaxPages = 5000;

        @Min(1024)
        private long comicPageMaxBytes = 104857600L;

        @Min(1024)
        private long comicInfoMaxBytes = 65536L;

        @Min(1)
        @Max(1000000)
        private int archiveMaxEntries = 50000;

        @Min(1024)
        private long archiveEntryMaxBytes = 21474836480L;

        @Min(1024)
        private long archiveTotalMaxBytes = 107374182400L;

        @Min(1)
        private int archiveMaxCompressionRatio = 1000;

        @Min(1024)
        private int archiveMaxMemoryKiB = 262144;

        @Min(1)
        @Max(512)
        private int archiveManifestCacheEntries = 32;

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

        public long getTextDraftRetentionHours() {
            return textDraftRetentionHours;
        }

        public void setTextDraftRetentionHours(long textDraftRetentionHours) {
            this.textDraftRetentionHours = textDraftRetentionHours;
        }

        public long getTextDraftCleanupIntervalMs() {
            return textDraftCleanupIntervalMs;
        }

        public void setTextDraftCleanupIntervalMs(long textDraftCleanupIntervalMs) {
            this.textDraftCleanupIntervalMs = textDraftCleanupIntervalMs;
        }

        public long getTextDraftLeaseSeconds() {
            return textDraftLeaseSeconds;
        }

        public void setTextDraftLeaseSeconds(long textDraftLeaseSeconds) {
            this.textDraftLeaseSeconds = textDraftLeaseSeconds;
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

        public int getArchiveMaxEntries() {
            return archiveMaxEntries;
        }

        public void setArchiveMaxEntries(int archiveMaxEntries) {
            this.archiveMaxEntries = archiveMaxEntries;
        }

        public long getArchiveEntryMaxBytes() {
            return archiveEntryMaxBytes;
        }

        public void setArchiveEntryMaxBytes(long archiveEntryMaxBytes) {
            this.archiveEntryMaxBytes = archiveEntryMaxBytes;
        }

        public long getArchiveTotalMaxBytes() {
            return archiveTotalMaxBytes;
        }

        public void setArchiveTotalMaxBytes(long archiveTotalMaxBytes) {
            this.archiveTotalMaxBytes = archiveTotalMaxBytes;
        }

        public int getArchiveMaxCompressionRatio() {
            return archiveMaxCompressionRatio;
        }

        public void setArchiveMaxCompressionRatio(int archiveMaxCompressionRatio) {
            this.archiveMaxCompressionRatio = archiveMaxCompressionRatio;
        }

        public int getArchiveMaxMemoryKiB() {
            return archiveMaxMemoryKiB;
        }

        public void setArchiveMaxMemoryKiB(int archiveMaxMemoryKiB) {
            this.archiveMaxMemoryKiB = archiveMaxMemoryKiB;
        }

        public int getArchiveManifestCacheEntries() {
            return archiveManifestCacheEntries;
        }

        public void setArchiveManifestCacheEntries(int archiveManifestCacheEntries) {
            this.archiveManifestCacheEntries = archiveManifestCacheEntries;
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

    public static class Share {
        private boolean enabled = true;

        @Min(0)
        private int defaultExpirationDays = 0;

        private boolean allowNeverExpires = true;

        @Min(0)
        private int maxExpirationDays = 0;

        private boolean customTokenEnabled = true;

        @Min(1)
        private int customTokenMinLength = 12;

        @Min(1)
        private int customTokenMaxLength = 64;

        @Min(8)
        @Max(64)
        private int randomTokenBytes = 24;

        private boolean directoryShareEnabled = true;

        private boolean directDownloadLinkEnabled = true;

        private boolean directoryShowHiddenItems = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultExpirationDays() {
            return defaultExpirationDays;
        }

        public void setDefaultExpirationDays(int defaultExpirationDays) {
            this.defaultExpirationDays = defaultExpirationDays;
        }

        public boolean isAllowNeverExpires() {
            return allowNeverExpires;
        }

        public void setAllowNeverExpires(boolean allowNeverExpires) {
            this.allowNeverExpires = allowNeverExpires;
        }

        public int getMaxExpirationDays() {
            return maxExpirationDays;
        }

        public void setMaxExpirationDays(int maxExpirationDays) {
            this.maxExpirationDays = maxExpirationDays;
        }

        public boolean isCustomTokenEnabled() {
            return customTokenEnabled;
        }

        public void setCustomTokenEnabled(boolean customTokenEnabled) {
            this.customTokenEnabled = customTokenEnabled;
        }

        public int getCustomTokenMinLength() {
            return customTokenMinLength;
        }

        public void setCustomTokenMinLength(int customTokenMinLength) {
            this.customTokenMinLength = customTokenMinLength;
        }

        public int getCustomTokenMaxLength() {
            return customTokenMaxLength;
        }

        public void setCustomTokenMaxLength(int customTokenMaxLength) {
            this.customTokenMaxLength = customTokenMaxLength;
        }

        public int getRandomTokenBytes() {
            return randomTokenBytes;
        }

        public void setRandomTokenBytes(int randomTokenBytes) {
            this.randomTokenBytes = randomTokenBytes;
        }

        public boolean isDirectoryShareEnabled() {
            return directoryShareEnabled;
        }

        public void setDirectoryShareEnabled(boolean directoryShareEnabled) {
            this.directoryShareEnabled = directoryShareEnabled;
        }

        public boolean isDirectDownloadLinkEnabled() {
            return directDownloadLinkEnabled;
        }

        public void setDirectDownloadLinkEnabled(boolean directDownloadLinkEnabled) {
            this.directDownloadLinkEnabled = directDownloadLinkEnabled;
        }

        public boolean isDirectoryShowHiddenItems() {
            return directoryShowHiddenItems;
        }

        public void setDirectoryShowHiddenItems(boolean directoryShowHiddenItems) {
            this.directoryShowHiddenItems = directoryShowHiddenItems;
        }
    }

    public static class Upload {
        @Min(1048576)
        @Max(67108864)
        private long resumableChunkSizeBytes = 33554432L;

        @Min(1)
        @Max(16)
        private int maxConcurrentChunks = 4;

        @Min(1)
        @Max(168)
        private int resumableSessionRetentionHours = 24;

        @Min(60000)
        private long resumableCleanupIntervalMs = 600000L;

        public long getResumableChunkSizeBytes() {
            return resumableChunkSizeBytes;
        }

        public void setResumableChunkSizeBytes(long resumableChunkSizeBytes) {
            this.resumableChunkSizeBytes = resumableChunkSizeBytes;
        }

        public int getMaxConcurrentChunks() {
            return maxConcurrentChunks;
        }

        public void setMaxConcurrentChunks(int maxConcurrentChunks) {
            this.maxConcurrentChunks = maxConcurrentChunks;
        }

        public int getResumableSessionRetentionHours() {
            return resumableSessionRetentionHours;
        }

        public void setResumableSessionRetentionHours(int resumableSessionRetentionHours) {
            this.resumableSessionRetentionHours = resumableSessionRetentionHours;
        }

        public long getResumableCleanupIntervalMs() {
            return resumableCleanupIntervalMs;
        }

        public void setResumableCleanupIntervalMs(long resumableCleanupIntervalMs) {
            this.resumableCleanupIntervalMs = resumableCleanupIntervalMs;
        }
    }

    public static class FileRequest {
        private boolean enabled = true;

        @Min(0)
        @Max(365)
        private int defaultExpirationDays = 7;

        @Min(1)
        @Max(21474836480L)
        private long defaultMaxFileSizeBytes = 10737418240L;

        @Min(1)
        @Max(107374182400L)
        private long defaultMaxTotalBytes = 21474836480L;

        @Min(1)
        @Max(1000)
        private int defaultMaxFiles = 100;

        private String defaultUploaderNamePolicy = "optional";

        private boolean customTokenEnabled = true;

        @Min(1)
        private int customTokenMinLength = 12;

        @Min(1)
        private int customTokenMaxLength = 64;

        @Min(8)
        @Max(64)
        private int randomTokenBytes = 24;

        @Min(1)
        @Max(2)
        private int maxConcurrentUploadsPerRequest = 2;

        private boolean rateLimitEnabled = true;

        @Min(1)
        @Max(100000)
        private int rateLimitMaxAdmissions = 120;

        @Min(1)
        @Max(86400)
        private int rateLimitWindowSeconds = 60;

        @Min(0)
        @Max(86400)
        private int accessLogDedupSeconds = 600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultExpirationDays() {
            return defaultExpirationDays;
        }

        public void setDefaultExpirationDays(int defaultExpirationDays) {
            this.defaultExpirationDays = defaultExpirationDays;
        }

        public long getDefaultMaxFileSizeBytes() {
            return defaultMaxFileSizeBytes;
        }

        public void setDefaultMaxFileSizeBytes(long defaultMaxFileSizeBytes) {
            this.defaultMaxFileSizeBytes = defaultMaxFileSizeBytes;
        }

        public long getDefaultMaxTotalBytes() {
            return defaultMaxTotalBytes;
        }

        public void setDefaultMaxTotalBytes(long defaultMaxTotalBytes) {
            this.defaultMaxTotalBytes = defaultMaxTotalBytes;
        }

        public int getDefaultMaxFiles() {
            return defaultMaxFiles;
        }

        public void setDefaultMaxFiles(int defaultMaxFiles) {
            this.defaultMaxFiles = defaultMaxFiles;
        }

        public String getDefaultUploaderNamePolicy() {
            return defaultUploaderNamePolicy;
        }

        public void setDefaultUploaderNamePolicy(String defaultUploaderNamePolicy) {
            this.defaultUploaderNamePolicy = defaultUploaderNamePolicy;
        }

        public boolean isCustomTokenEnabled() {
            return customTokenEnabled;
        }

        public void setCustomTokenEnabled(boolean customTokenEnabled) {
            this.customTokenEnabled = customTokenEnabled;
        }

        public int getCustomTokenMinLength() {
            return customTokenMinLength;
        }

        public void setCustomTokenMinLength(int customTokenMinLength) {
            this.customTokenMinLength = customTokenMinLength;
        }

        public int getCustomTokenMaxLength() {
            return customTokenMaxLength;
        }

        public void setCustomTokenMaxLength(int customTokenMaxLength) {
            this.customTokenMaxLength = customTokenMaxLength;
        }

        public int getRandomTokenBytes() {
            return randomTokenBytes;
        }

        public void setRandomTokenBytes(int randomTokenBytes) {
            this.randomTokenBytes = randomTokenBytes;
        }

        public int getMaxConcurrentUploadsPerRequest() {
            return maxConcurrentUploadsPerRequest;
        }

        public void setMaxConcurrentUploadsPerRequest(int maxConcurrentUploadsPerRequest) {
            this.maxConcurrentUploadsPerRequest = maxConcurrentUploadsPerRequest;
        }

        public boolean isRateLimitEnabled() {
            return rateLimitEnabled;
        }

        public void setRateLimitEnabled(boolean rateLimitEnabled) {
            this.rateLimitEnabled = rateLimitEnabled;
        }

        public int getRateLimitMaxAdmissions() {
            return rateLimitMaxAdmissions;
        }

        public void setRateLimitMaxAdmissions(int rateLimitMaxAdmissions) {
            this.rateLimitMaxAdmissions = rateLimitMaxAdmissions;
        }

        public int getRateLimitWindowSeconds() {
            return rateLimitWindowSeconds;
        }

        public void setRateLimitWindowSeconds(int rateLimitWindowSeconds) {
            this.rateLimitWindowSeconds = rateLimitWindowSeconds;
        }

        public int getAccessLogDedupSeconds() {
            return accessLogDedupSeconds;
        }

        public void setAccessLogDedupSeconds(int accessLogDedupSeconds) {
            this.accessLogDedupSeconds = accessLogDedupSeconds;
        }
    }

    public static class PendingFileDecisions {
        @Min(1)
        @Max(3650)
        private int warningDays = 30;

        public int getWarningDays() {
            return warningDays;
        }

        public void setWarningDays(int warningDays) {
            this.warningDays = warningDays;
        }
    }

    public static class TemporaryArtifacts {
        @Min(1)
        private int staleAfterMinutes = 30;

        public int getStaleAfterMinutes() {
            return staleAfterMinutes;
        }

        public void setStaleAfterMinutes(int staleAfterMinutes) {
            this.staleAfterMinutes = staleAfterMinutes;
        }
    }

    public static class ActivityLog {
        private boolean enabled = true;

        @Min(1024)
        private long maxFileSizeBytes = 10L * 1024L * 1024L;

        @Min(0)
        private int maxArchiveFiles = 30;

        @Min(1)
        @Max(1000)
        private int defaultPageSize = 100;

        @NotNull
        private List<Integer> pageSizeOptions = List.of(50, 100, 200, 500);

        private boolean allowArchiveDelete = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }

        public int getMaxArchiveFiles() {
            return maxArchiveFiles;
        }

        public void setMaxArchiveFiles(int maxArchiveFiles) {
            this.maxArchiveFiles = maxArchiveFiles;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public List<Integer> getPageSizeOptions() {
            return pageSizeOptions;
        }

        public void setPageSizeOptions(List<Integer> pageSizeOptions) {
            this.pageSizeOptions = pageSizeOptions == null ? List.of(50, 100, 200, 500) : pageSizeOptions;
        }

        public boolean isAllowArchiveDelete() {
            return allowArchiveDelete;
        }

        public void setAllowArchiveDelete(boolean allowArchiveDelete) {
            this.allowArchiveDelete = allowArchiveDelete;
        }
    }

    public static class Tasks {
        @Min(1)
        @Max(10000)
        private int historyLimit = 100;

        @Min(1)
        @Max(16)
        private int workerThreads = 2;

        private boolean activityPanelEnabled = true;

        @Min(0)
        private int completedDisplayMs = 2800;

        @Min(0)
        private int failedDisplayMs = 7000;

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public boolean isActivityPanelEnabled() {
            return activityPanelEnabled;
        }

        public void setActivityPanelEnabled(boolean activityPanelEnabled) {
            this.activityPanelEnabled = activityPanelEnabled;
        }

        public int getCompletedDisplayMs() {
            return completedDisplayMs;
        }

        public void setCompletedDisplayMs(int completedDisplayMs) {
            this.completedDisplayMs = completedDisplayMs;
        }

        public int getFailedDisplayMs() {
            return failedDisplayMs;
        }

        public void setFailedDisplayMs(int failedDisplayMs) {
            this.failedDisplayMs = failedDisplayMs;
        }
    }

    public static class MetadataInspector {
        private boolean enabled = true;

        @Min(0)
        private int maxIssuesPerArea = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxIssuesPerArea() {
            return maxIssuesPerArea;
        }

        public void setMaxIssuesPerArea(int maxIssuesPerArea) {
            this.maxIssuesPerArea = maxIssuesPerArea;
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
