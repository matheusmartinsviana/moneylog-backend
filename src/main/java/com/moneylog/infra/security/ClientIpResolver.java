package com.moneylog.infra.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

public class ClientIpResolver {

    private final List<Cidr> trusted;

    public ClientIpResolver(List<String> trustedProxies) {
        this.trusted = trustedProxies.stream().map(Cidr::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (trusted.isEmpty() || !isTrusted(remote)) {
            return remote;
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return remote;
        }
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!hop.isEmpty() && !isTrusted(hop)) {
                return hop;
            }
        }
        return remote;
    }

    private boolean isTrusted(String ip) {
        return trusted.stream().anyMatch(cidr -> cidr.contains(ip));
    }

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            String[] parts = text.strip().split("/");
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : address.length * 8;
                if (prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalArgumentException("prefixo inválido");
                }
                return new Cidr(address, prefix);
            } catch (UnknownHostException | IllegalArgumentException e) {
                throw new IllegalStateException("moneylog.security.trusted-proxies tem um CIDR inválido: " + text, e);
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Cidr that && prefix == that.prefix && Arrays.equals(network, that.network);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(network), prefix);
        }

        @Override
        public String toString() {
            return "Cidr[bytes=" + network.length + ", prefix=" + prefix + "]";
        }

        boolean contains(String ip) {
            if (!ip.matches("[0-9a-fA-F:.]+")) {
                return false;
            }
            byte[] candidate;
            try {
                candidate = InetAddress.getByName(ip).getAddress();
            } catch (UnknownHostException e) {
                return false;
            }
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
