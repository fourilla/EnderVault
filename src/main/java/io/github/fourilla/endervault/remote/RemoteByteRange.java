package io.github.fourilla.endervault.remote;

record RemoteByteRange(long start, long end, String ifRange) {

    RemoteByteRange {
        if (start < 0L || end < start) {
            throw new IllegalArgumentException("Byte range is invalid.");
        }
        ifRange = ifRange == null ? "" : ifRange;
    }

    String headerValue() {
        return "bytes=" + start + "-" + end;
    }

    long length() {
        return end - start + 1L;
    }
}
