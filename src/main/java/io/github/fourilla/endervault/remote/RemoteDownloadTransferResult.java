package io.github.fourilla.endervault.remote;

record RemoteDownloadTransferResult(
        String fileName,
        String targetPath,
        String pendingDecisionId
) {

    static RemoteDownloadTransferResult committed(String fileName, String targetPath) {
        return new RemoteDownloadTransferResult(fileName, targetPath, null);
    }

    static RemoteDownloadTransferResult pending(String fileName, String targetPath, String pendingDecisionId) {
        return new RemoteDownloadTransferResult(fileName, targetPath, pendingDecisionId);
    }

    boolean pending() {
        return pendingDecisionId != null;
    }
}
