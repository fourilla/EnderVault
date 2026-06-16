package io.github.fourilla.endervault.passkey;

import java.util.ArrayList;
import java.util.List;

public class PasskeyStore {
    private String userHandle;
    private List<PasskeyCredential> credentials = new ArrayList<>();

    public String getUserHandle() {
        return userHandle;
    }

    public void setUserHandle(String userHandle) {
        this.userHandle = userHandle;
    }

    public List<PasskeyCredential> getCredentials() {
        return credentials == null ? new ArrayList<>() : credentials;
    }

    public void setCredentials(List<PasskeyCredential> credentials) {
        this.credentials = credentials == null ? new ArrayList<>() : new ArrayList<>(credentials);
    }
}
