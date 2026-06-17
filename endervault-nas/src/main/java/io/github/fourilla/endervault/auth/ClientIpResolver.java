package io.github.fourilla.endervault.auth;

import io.github.fourilla.endervault.config.NasProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<TrustedProxy> trustedProxies;

    public ClientIpResolver(NasProperties nasProperties) {
        List<String> configuredProxies = nasProperties == null || nasProperties.getSecurity() == null
                ? List.of()
                : nasProperties.getSecurity().getTrustedProxies();
        this.trustedProxies = configuredProxies.stream()
                .map(TrustedProxy::parse)
                .flatMap(Optional::stream)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "system";
        }

        String remoteAddress = cleanIpToken(request.getRemoteAddr());
        if (isTrustedProxy(remoteAddress)) {
            String forwardedAddress = forwardedAddress(request.getHeader(X_FORWARDED_FOR));
            if (forwardedAddress != null) {
                return forwardedAddress;
            }
        }
        return remoteAddress == null || remoteAddress.isBlank() ? "-" : remoteAddress;
    }

    private String forwardedAddress(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        List<String> addresses = Arrays.stream(header.split(","))
                .map(ClientIpResolver::cleanIpToken)
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> parseIpLiteral(value).isPresent())
                .toList();
        if (addresses.isEmpty()) {
            return null;
        }

        for (int index = addresses.size() - 1; index >= 0; index--) {
            String address = addresses.get(index);
            if (!isTrustedProxy(address)) {
                return address;
            }
        }
        return addresses.get(0);
    }

    private boolean isTrustedProxy(String address) {
        Optional<InetAddress> parsedAddress = parseIpLiteral(address);
        return parsedAddress.isPresent()
                && trustedProxies.stream().anyMatch(proxy -> proxy.matches(parsedAddress.get()));
    }

    private static String cleanIpToken(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']')).trim();
        }

        int colonIndex = value.indexOf(':');
        if (colonIndex > 0 && colonIndex == value.lastIndexOf(':') && value.substring(0, colonIndex).contains(".")) {
            return value.substring(0, colonIndex).trim();
        }
        return value;
    }

    private static Optional<InetAddress> parseIpLiteral(String value) {
        if (!looksLikeIpLiteral(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value));
        } catch (UnknownHostException ex) {
            return Optional.empty();
        }
    }

    private static boolean looksLikeIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || (value.contains(":") && value.matches("[0-9A-Fa-f:.%]+"));
    }

    private record TrustedProxy(InetAddress address, int prefixLength) {

        private static Optional<TrustedProxy> parse(String value) {
            String proxy = cleanIpToken(value);
            if (proxy == null || proxy.isBlank()) {
                return Optional.empty();
            }

            String addressPart = proxy;
            Integer prefixLength = null;
            int slashIndex = proxy.indexOf('/');
            if (slashIndex >= 0) {
                addressPart = proxy.substring(0, slashIndex);
                try {
                    prefixLength = Integer.parseInt(proxy.substring(slashIndex + 1));
                } catch (NumberFormatException ex) {
                    return Optional.empty();
                }
            }

            Optional<InetAddress> address = parseIpLiteral(addressPart);
            if (address.isEmpty()) {
                return Optional.empty();
            }

            int addressBits = address.get().getAddress().length * Byte.SIZE;
            int safePrefixLength = prefixLength == null ? addressBits : prefixLength;
            if (safePrefixLength < 0 || safePrefixLength > addressBits) {
                return Optional.empty();
            }
            return Optional.of(new TrustedProxy(address.get(), safePrefixLength));
        }

        private boolean matches(InetAddress candidate) {
            byte[] trustedBytes = address.getAddress();
            byte[] candidateBytes = candidate.getAddress();
            if (trustedBytes.length != candidateBytes.length) {
                return false;
            }
            return matchesPrefix(trustedBytes, candidateBytes, prefixLength);
        }

        private static boolean matchesPrefix(byte[] trustedBytes, byte[] candidateBytes, int prefixLength) {
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;

            for (int index = 0; index < fullBytes; index++) {
                if (trustedBytes[index] != candidateBytes[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }

            int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
            return (trustedBytes[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
        }
    }
}
