package com.wl.zotecAgent;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Auth headers for document processor APIs.
 * Uses desk credentials ({@code x-bot-secret}, {@code x-desktop-id}) plus static JWT
 * ({@code Authorization: Bearer …} from {@code bearer.token}). No session cookie.
 */
final class DocumentProcessorAuth {

    static final String API_KEY_HEADER = "x-api-key";
    static final String BOT_SECRET_HEADER = "x-bot-secret";
    static final String DESKTOP_ID_HEADER = "x-desktop-id";

    private DocumentProcessorAuth() {}

    /** Require non-blank static JWT. */
    static String requireBearer(String bearerToken) {
	if (bearerToken == null || bearerToken.isBlank()) {
	    throw new IllegalStateException("bearer.token is required for document API auth (Authorization: Bearer)");
	}
	return bearerToken.trim();
    }

    /**
     * Headers for {@code POST /auth/login} — static JWT Bearer only (non-expiring).
     */
    static HttpHeaders loginHeaders(String bearerToken) {
	HttpHeaders headers = new HttpHeaders();
	headers.setContentType(MediaType.APPLICATION_JSON);
	headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
	headers.setBearerAuth(requireBearer(bearerToken));
	return headers;
    }

    /**
     * Headers for upload / review / documents — desk id/secret + static JWT Bearer.
     */
    static HttpHeaders uploadHeaders(String desktopId, String botSecret, String bearerToken) {
	if (desktopId == null || desktopId.isBlank()) {
	    throw new IllegalStateException("bot.poll.desktop-id is required for document API auth");
	}
	if (botSecret == null || botSecret.isBlank()) {
	    throw new IllegalStateException("bot.poll.secret is required for document API auth (x-bot-secret)");
	}
	HttpHeaders headers = new HttpHeaders();
	headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
	headers.set(BOT_SECRET_HEADER, botSecret.trim());
	headers.set(DESKTOP_ID_HEADER, desktopId.trim());
	headers.setBearerAuth(requireBearer(bearerToken));
	return headers;
    }

    /** @deprecated Prefer {@link #uploadHeaders}; kept if any caller still uses API key. */
    static HttpHeaders headers(String apiKey) {
	if (apiKey == null || apiKey.isBlank()) {
	    throw new IllegalStateException("document.api-key is required — set in application.properties");
	}
	HttpHeaders headers = new HttpHeaders();
	headers.set(API_KEY_HEADER, apiKey.trim());
	return headers;
    }

    static String maskKey(String apiKey) {
	if (apiKey == null || apiKey.length() < 12) {
	    return "****";
	}
	return apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    static String maskSecret(String secret) {
	if (secret == null || secret.length() < 4) {
	    return "****";
	}
	if (secret.length() < 8) {
	    return secret.substring(0, 2) + "****";
	}
	return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 2);
    }

    static String maskBearer(String bearerToken) {
	return maskSecret(bearerToken);
    }
}
