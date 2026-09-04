package io.github.fourilla.endervault.remote;

record RemoteResourceIdentity(long totalBytes, String strongEtag, String lastModified) {

    RemoteResourceIdentity {
        strongEtag = strongEtag == null ? "" : strongEtag;
        lastModified = lastModified == null ? "" : lastModified;
    }

    String ifRange() {
        if (!strongEtag.isBlank()) {
            return strongEtag;
        }
        return lastModified;
    }

    boolean matches(RemoteHttpResponse response) {
        if (!strongEtag.isBlank()) {
            return response.strongEtag().isBlank() || strongEtag.equals(response.strongEtag());
        }
        if (!lastModified.isBlank()) {
            return response.lastModified().isBlank() || lastModified.equals(response.lastModified());
        }
        return true;
    }
}
