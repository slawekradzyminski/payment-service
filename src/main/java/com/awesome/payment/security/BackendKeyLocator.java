package com.awesome.payment.security;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.JwkSet;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.InvalidKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the RS256 verification key of a backend access token by its {@code kid} from the
 * backend's JWKS ({@code /internal/v1/jwks.json}). Keys are cached; an unknown {@code kid}
 * (the backend rotated its key, or restarted with an ephemeral one) triggers a refetch, at
 * most once per {@code security.jwt.jwks-refresh-interval}, so forged kids cannot flood the
 * backend. Only RS256 is accepted, which also rules out HMAC key-confusion tokens. Concurrent
 * requests with a kid that is not cached yet (the first requests after a start or a key
 * rotation) wait for the one refetch instead of failing with 401, and a refetch swaps the whole
 * cache at once, so a known key never disappears while it runs.
 */
@Slf4j
@Component
public class BackendKeyLocator extends LocatorAdapter<Key> {

    static final String ALGORITHM = "RS256";

    private final BackendClient backendClient;
    private final Duration refreshInterval;
    private final Clock clock;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant lastRefresh = Instant.EPOCH;

    @Autowired
    public BackendKeyLocator(BackendClient backendClient,
                             @Value("${security.jwt.jwks-refresh-interval:10s}") Duration refreshInterval) {
        this(backendClient, refreshInterval, Clock.systemUTC());
    }

    BackendKeyLocator(BackendClient backendClient, Duration refreshInterval, Clock clock) {
        super();
        this.backendClient = backendClient;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
    }

    @Override
    protected Key locate(JwsHeader header) {
        if (!ALGORITHM.equals(header.getAlgorithm())) {
            throw new InvalidKeyException("Only RS256 access tokens are accepted");
        }
        String kid = header.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new InvalidKeyException("Access token has no key id");
        }
        PublicKey key = keys.get(kid);
        if (key == null) {
            key = refreshFor(kid);
        }
        if (key == null) {
            throw new InvalidKeyException("Unknown key id");
        }
        return key;
    }

    /**
     * Refetches the keys for an unknown kid. Serialised, so a request that waited while another
     * one refetched finds the kid in the fresh cache instead of being throttled.
     */
    private synchronized PublicKey refreshFor(String kid) {
        PublicKey key = keys.get(kid);
        if (key != null || !refreshAllowed()) {
            return key;
        }
        refresh();
        return keys.get(kid);
    }

    private boolean refreshAllowed() {
        Instant now = clock.instant();
        if (now.isBefore(lastRefresh.plus(refreshInterval))) {
            return false;
        }
        lastRefresh = now;
        return true;
    }

    /** Replaces the cache with the backend's current keys; a backend outage propagates as 503. */
    private void refresh() {
        JwkSet set = backendClient.jwks();
        Map<String, PublicKey> fresh = new HashMap<>();
        for (JwkSet.Jwk jwk : set.keys()) {
            if ("RSA".equals(jwk.kty()) && jwk.kid() != null && (jwk.alg() == null || ALGORITHM.equals(jwk.alg()))) {
                fresh.put(jwk.kid(), rsaKey(jwk));
            }
        }
        keys = Map.copyOf(fresh);
        log.info("Loaded {} access-token verification key(s) from the backend", fresh.size());
    }

    static PublicKey rsaKey(JwkSet.Jwk jwk) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(jwk.n())),
                    new BigInteger(1, Base64.getUrlDecoder().decode(jwk.e()))));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new InvalidKeyException("Backend published an invalid RSA key " + jwk.kid(), e);
        }
    }
}
