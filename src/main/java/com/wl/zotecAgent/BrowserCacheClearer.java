package com.wl.zotecAgent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.wl.util.BrowserCacheClear;

/**
 * Clears Zotec portal cookies / storage for the Chrome profile before the bot
 * closes the browser — without wiping extension data for other origins.
 */
public final class BrowserCacheClearer {

    private static final Logger log = LoggerFactory.getLogger(BrowserCacheClearer.class);

    private BrowserCacheClearer() {
    }

    /**
     * Clears session + HTTP cache for the configured Zotec portal origin only.
     *
     * @param context   Playwright context (may be null)
     * @param page      preferred page for CDP (may be null/closed)
     * @param portalUrl e.g. {@code https://radcoding.zotecpartners.com/}
     * @param when      log label (stopBot / cleanup)
     */
    public static void clearZotecSiteOnly(BrowserContext context, Page page, String portalUrl, String when) {
	if (context == null) {
	    return;
	}
	try {
	    String origin = portalUrl;
	    if (origin == null || origin.isBlank()) {
		BrowserCacheClear.clearSessionAndHttpCache(context);
		log.info("Cleared default Zotec origins at {}", when);
		return;
	    }
	    origin = origin.trim();
	    while (origin.endsWith("/")) {
		origin = origin.substring(0, origin.length() - 1);
	    }
	    BrowserCacheClear.clearSessionAndHttpCache(context, List.of(origin));
	    log.info("Cleared Zotec site cache for {} at {}", origin, when);
	} catch (Exception e) {
	    log.debug("clearZotecSiteOnly at {}: {}", when, e.getMessage());
	}
    }
}
