package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadValidator {

    private final NasProperties nasProperties;

    public RemoteDownloadValidator(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new StorageAccessException("Remote URL is required.");
        }

        try {
            return validate(new URI(rawUrl.trim()));
        } catch (URISyntaxException ex) {
            throw new StorageAccessException("Remote URL is invalid.");
        }
    }

    public URI validate(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new StorageAccessException("Remote URL must include a scheme and host.");
        }
        if (uri.getUserInfo() != null) {
            throw new StorageAccessException("Remote URL credentials are not allowed.");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new StorageAccessException("Only HTTP and HTTPS remote downloads are allowed.");
        }

        int port = effectivePort(uri);
        List<Integer> allowedPorts = nasProperties.getRemoteDownload().getAllowedPorts();
        if (allowedPorts != null && !allowedPorts.isEmpty() && !allowedPorts.contains(port)) {
            throw new StorageAccessException("Remote download port is not allowed: " + port);
        }

        validateResolvedAddresses(uri.getHost());
        return uri.normalize();
    }

    public URI validateRedirect(URI currentUri, String location) {
        if (location == null || location.isBlank()) {
            throw new StorageAccessException("Remote server returned an empty redirect.");
        }
        return validate(currentUri.resolve(location.trim()));
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void validateResolvedAddresses(String host) {
        try {
            String asciiHost = IDN.toASCII(host);
            InetAddress[] addresses = InetAddress.getAllByName(asciiHost);
            if (addresses.length == 0) {
                throw new StorageAccessException("Remote host did not resolve to an address.");
            }

            if (!nasProperties.getRemoteDownload().isBlockPrivateNetworks()) {
                return;
            }

            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new StorageAccessException("Remote host resolves to a blocked network address.");
                }
            }
        } catch (UnknownHostException ex) {
            throw new StorageAccessException("Remote host could not be resolved.");
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("Remote host is invalid.");
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
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

    private boolean isBlockedIpv4(byte[] bytes) {
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

    private boolean isBlockedIpv6(byte[] bytes) {
        if (isIpv4MappedIpv6(bytes)) {
            return isBlockedIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }

        int first = bytes[0] & 0xff;
        return (first & 0xfe) == 0xfc;
    }

    private boolean isIpv4MappedIpv6(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
