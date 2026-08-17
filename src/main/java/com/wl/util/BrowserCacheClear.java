package com.wl.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;

/**
 * Clears bot browser session data (A) and HTTP disk cache (B) for Zotec portal origins.
 * <ul>
 * <li><b>A</b> — cookies, localStorage, sessionStorage, Cache Storage</li>
 * <li><b>B</b> — {@code Network.clearBrowserCache}</li>
 * </ul>
 * Safe to call when context/pages are missing (best-effort no-op).
 */
public final class BrowserCacheClear {

    private static final Logger log = LogManager.getLogger(BrowserCacheClear.class);

    /** CDP Storage.clearDataForOrigin types for option A (sessionStorage via page JS). */
    private static final String STORAGE_TYPES_A = "cookies,local_storage,cache_storage";

    private BrowserCacheClear() {
    }

    /** Origins from {@code zotec.portal.URL} in application.properties / active profile. */
    public static List<String> defaultOrigins() {
	return List.of(AppProperties.zotecPortalOrigin());
    }

    /**
     * Clears A+B for {@link #defaultOrigins()} using the given context.
     *
     * @param context Playwright browser context (may be null)
     */
    public static void clearSessionAndHttpCache(BrowserContext context) {
	clearSessionAndHttpCache(context, defaultOrigins());
    }

    /**
     * Clears A+B for the given origins.
     *
     * @param context Playwright browser context (may be null)
     * @param origins absolute origins from {@code zotec.portal.URL}
     */
    public static void clearSessionAndHttpCache(BrowserContext context, List<String> origins) {
	if (context == null) {
	    log.debug("BrowserCacheClear skipped — context is null");
	    return;
	}
	List<String> originList = normalizeOrigins(origins);
	log.info("BrowserCacheClear A+B starting for origins={}", originList);

	// A — cookies (context-wide)
	try {
	    context.clearCookies();
	    log.info("BrowserCacheClear A — cookies cleared");
	} catch (Exception e) {
	    log.warn("BrowserCacheClear A — clearCookies failed: {}", e.getMessage());
	}

	Page cdpPage = null;
	boolean createdTempPage = false;
	CDPSession cdp = null;
	try {
	    List<Page> pages = context.pages();
	    if (pages != null && !pages.isEmpty()) {
		cdpPage = pages.get(0);
	    } else {
		cdpPage = context.newPage();
		createdTempPage = true;
	    }
	    cdp = context.newCDPSession(cdpPage);

	    // B — HTTP disk cache
	    try {
		cdp.send("Network.clearBrowserCache", new JsonObject());
		log.info("BrowserCacheClear B — Network.clearBrowserCache done");
	    } catch (Exception e) {
		log.warn("BrowserCacheClear B — clearBrowserCache failed: {}", e.getMessage());
	    }

	    // A — origin storage via CDP (cookies + local_storage + cache_storage)
	    for (String origin : originList) {
		try {
		    JsonObject params = new JsonObject();
		    params.addProperty("origin", origin);
		    params.addProperty("storageTypes", STORAGE_TYPES_A);
		    cdp.send("Storage.clearDataForOrigin", params);
		    log.info("BrowserCacheClear A — Storage.clearDataForOrigin origin={}", origin);
		} catch (Exception e) {
		    log.warn("BrowserCacheClear A — clearDataForOrigin failed for {}: {}", origin,
			    e.getMessage());
		}
	    }
	} catch (Exception e) {
	    log.warn("BrowserCacheClear CDP session failed: {}", e.getMessage());
	} finally {
	    try {
		if (cdp != null) {
		    cdp.detach();
		}
	    } catch (Exception ignored) {
	    }
	    if (createdTempPage && cdpPage != null) {
		try {
		    cdpPage.close();
		} catch (Exception ignored) {
		}
	    }
	}

	// A — sessionStorage (+ localStorage backup) on open pages for tracked origins
	clearPageWebStorage(context, originList);

	log.info("BrowserCacheClear A+B finished");
    }

    private static void clearPageWebStorage(BrowserContext context, List<String> origins) {
	Set<String> originSet = new LinkedHashSet<>(origins);
	List<Page> pages;
	try {
	    pages = context.pages();
	} catch (Exception e) {
	    return;
	}
	if (pages == null) {
	    return;
	}
	for (Page page : pages) {
	    try {
		String url = page.url();
		String pageOrigin = originFromUrl(url);
		if (pageOrigin == null || !originSet.contains(pageOrigin)) {
		    continue;
		}
		page.evaluate("() => {"
			+ "  try { sessionStorage.clear(); } catch (e) {}"
			+ "  try { localStorage.clear(); } catch (e) {}"
			+ "  try {"
			+ "    if (window.caches && caches.keys) {"
			+ "      return caches.keys().then(keys => Promise.all(keys.map(k => caches.delete(k))));"
			+ "    }"
			+ "  } catch (e) {}"
			+ "}");
		log.info("BrowserCacheClear A — page web storage cleared for {}", pageOrigin);
	    } catch (Exception e) {
		log.debug("BrowserCacheClear A — page storage clear skipped: {}", e.getMessage());
	    }
	}
    }

    private static List<String> normalizeOrigins(List<String> origins) {
	if (origins == null || origins.isEmpty()) {
	    return new ArrayList<>(defaultOrigins());
	}
	List<String> out = new ArrayList<>();
	for (String o : origins) {
	    if (o == null || o.isBlank()) {
		continue;
	    }
	    String trimmed = o.trim();
	    // Strip trailing slash
	    if (trimmed.endsWith("/")) {
		trimmed = trimmed.substring(0, trimmed.length() - 1);
	    }
	    out.add(trimmed);
	}
	return out.isEmpty() ? new ArrayList<>(defaultOrigins()) : out;
    }

    private static String originFromUrl(String url) {
	if (url == null || url.isBlank() || url.startsWith("about:") || url.startsWith("chrome:")) {
	    return null;
	}
	try {
	    java.net.URI uri = java.net.URI.create(url);
	    String scheme = uri.getScheme();
	    String host = uri.getHost();
	    if (scheme == null || host == null) {
		return null;
	    }
	    int port = uri.getPort();
	    if (port > 0 && port != 80 && port != 443) {
		return scheme + "://" + host + ":" + port;
	    }
	    return scheme + "://" + host;
	} catch (Exception e) {
	    return null;
	}
    }
}
