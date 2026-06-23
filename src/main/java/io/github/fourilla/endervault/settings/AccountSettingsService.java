package io.github.fourilla.endervault.settings;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.passkey.PasskeyService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class AccountSettingsService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final int MIN_PASSWORD_LENGTH = 4;

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;
    private final PasswordEncoder passwordEncoder;
    private final InMemoryUserDetailsManager userDetailsManager;
    private final PasskeyService passkeyService;

    public AccountSettingsService(
            NasProperties nasProperties,
            LocalPropertiesFile localPropertiesFile,
            PasswordEncoder passwordEncoder,
            InMemoryUserDetailsManager userDetailsManager,
            PasskeyService passkeyService
    ) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsManager = userDetailsManager;
        this.passkeyService = passkeyService;
    }

    public AccountSettingsSnapshot currentSettings() {
        return new AccountSettingsSnapshot(
                nasProperties.getAdmin().getUsername(),
                nasProperties.getPasskeys().isPasswordLoginEnabled(),
                nasProperties.getPasskeys().isEnabled(),
                passkeyService.listCredentials().size(),
                localPropertiesFile.configFile().toString()
        );
    }

    public AccountSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        String username = clean(first(parameters, "username"));
        validateUsername(username);

        String currentPassword = first(parameters, "currentPassword");
        String newPassword = first(parameters, "newPassword");
        String confirmPassword = first(parameters, "confirmPassword");
        boolean passwordLoginEnabled = parameters.containsKey("passwordLoginEnabled");

        String currentUsername = nasProperties.getAdmin().getUsername();
        boolean usernameChanged = !username.equals(currentUsername);
        boolean passwordChangeRequested = !newPassword.isBlank() || !confirmPassword.isBlank();

        if ((usernameChanged || passwordChangeRequested)
                && !passwordEncoder.matches(currentPassword, nasProperties.getAdmin().getPassword())) {
            throw new IllegalArgumentException("Current password is required to change the admin ID or password.");
        }

        String encodedPassword = nasProperties.getAdmin().getPassword();
        if (passwordChangeRequested) {
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("New password and confirmation do not match.");
            }
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("New password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }
            encodedPassword = passwordEncoder.encode(newPassword);
        }

        if (!passwordLoginEnabled && (!passkeyService.isEnabled() || !passkeyService.hasCredentials())) {
            throw new IllegalArgumentException("Register at least one passkey before disabling ID/password login.");
        }

        return new AccountSettingsUpdate(
                username,
                encodedPassword,
                passwordLoginEnabled,
                usernameChanged,
                passwordChangeRequested,
                passwordLoginEnabled != nasProperties.getPasskeys().isPasswordLoginEnabled()
        );
    }

    public void save(AccountSettingsUpdate update) throws IOException {
        String oldUsername = nasProperties.getAdmin().getUsername();
        persist(update);
        applyToRuntime(oldUsername, update);
    }

    private void persist(AccountSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("nas.admin.username", update.username());
        updates.put("nas.admin.password", update.encodedPassword());
        updates.put("nas.passkeys.password-login-enabled", Boolean.toString(update.passwordLoginEnabled()));
        localPropertiesFile.update(updates, "# Account settings managed from EnderVault Settings.");
    }

    private void applyToRuntime(String oldUsername, AccountSettingsUpdate update) {
        nasProperties.getAdmin().setUsername(update.username());
        nasProperties.getAdmin().setPassword(update.encodedPassword());
        nasProperties.getPasskeys().setPasswordLoginEnabled(update.passwordLoginEnabled());

        UserDetails admin = User.withUsername(update.username())
                .password(passwordForRuntime(update))
                .roles("ADMIN")
                .build();
        if (!oldUsername.equals(update.username()) && userDetailsManager.userExists(oldUsername)) {
            userDetailsManager.deleteUser(oldUsername);
        }
        if (userDetailsManager.userExists(update.username())) {
            userDetailsManager.updateUser(admin);
        } else {
            userDetailsManager.createUser(admin);
        }
    }

    private String passwordForRuntime(AccountSettingsUpdate update) {
        return update.passwordLoginEnabled()
                ? update.encodedPassword()
                : "{noop}" + UUID.randomUUID();
    }

    private static void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Admin ID must be 3-64 characters using letters, numbers, dot, underscore, or hyphen."
            );
        }
    }

    private static String first(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    public record AccountSettingsSnapshot(
            String username,
            boolean passwordLoginEnabled,
            boolean passkeysEnabled,
            int passkeyCount,
            String configPath
    ) {
    }

    public record AccountSettingsUpdate(
            String username,
            String encodedPassword,
            boolean passwordLoginEnabled,
            boolean usernameChanged,
            boolean passwordChanged,
            boolean passwordLoginPolicyChanged
    ) {
    }
}
