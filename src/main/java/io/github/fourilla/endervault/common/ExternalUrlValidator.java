package io.github.fourilla.endervault.common;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

public final class ExternalUrlValidator {

    private ExternalUrlValidator() {
    }

    public static URI validate(String rawUrl, Policy policy) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new StorageAccessException(policy.urlLabel() + " is required.");
        }

        try {
            return validate(new URI(rawUrl.trim()), policy);
        } catch (URISyntaxException ex) {
            throw new StorageAccessException(policy.urlLabel() + " is invalid.");
        }
    }

    public static URI validate(URI uri, Policy policy) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new StorageAccessException(policy.urlLabel() + " must include a scheme and host.");
        }
        if (uri.getUserInfo() != null) {
            throw new StorageAccessException(policy.urlLabel() + " credentials are not allowed.");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new StorageAccessException("Only HTTP and HTTPS " + policy.operationLabel() + " are allowed.");
        }

        int port = effectivePort(uri);
        List<Integer> allowedPorts = policy.allowedPorts();
        if (allowedPorts != null && !allowedPorts.isEmpty() && !allowedPorts.contains(port)) {
            throw new StorageAccessException(policy.portLabel() + " is not allowed: " + port);
        }

        validateResolvedAddresses(uri.getHost(), policy);
        return uri.normalize();
    }

    public static URI validateRedirect(URI currentUri, String location, Policy policy) {
        if (location == null || location.isBlank()) {
            throw new StorageAccessException(policy.redirectLabel() + " returned an empty redirect.");
        }
        return validate(currentUri.resolve(location.trim()), policy);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void validateResolvedAddresses(String host, Policy policy) {
        try {
            String asciiHost = IDN.toASCII(host);
            InetAddress[] addresses = InetAddress.getAllByName(asciiHost);
            if (addresses.length == 0) {
                throw new StorageAccessException(policy.hostLabel() + " did not resolve to an address.");
            }

            if (!policy.blockPrivateNetworks()) {
                return;
            }

            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new StorageAccessException(policy.hostLabel() + " resolves to a blocked network address.");
                }
            }
        } catch (UnknownHostException ex) {
            throw new StorageAccessException(policy.hostLabel() + " could not be resolved.");
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException(policy.hostLabel() + " is invalid.");
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            return isBlockedIpv6(bytes);
        }
        return true;
    }

    private static boolean isBlockedIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;

        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || first >= 224;
    }

    private static boolean isBlockedIpv6(byte[] bytes) {
        if (isIpv4MappedIpv6(bytes)) {
            return isBlockedIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }

        int first = bytes[0] & 0xff;
        return (first & 0xfe) == 0xfc;
    }

    private static boolean isIpv4MappedIpv6(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    public record Policy(
            String urlLabel,
            String operationLabel,
            String portLabel,
            String hostLabel,
            String redirectLabel,
            List<Integer> allowedPorts,
            boolean blockPrivateNetworks
    ) {
    }
}
