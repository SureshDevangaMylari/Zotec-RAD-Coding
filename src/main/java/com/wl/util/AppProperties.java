package com.wl.util;

import java.io.InputStream;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads {@code application.properties} (+ active Spring profile) from the classpath
 * for callers that are not Spring beans (e.g. {@code Service}, {@code BrowserCacheClear}).
 */
public final class AppProperties {

    private static final Logger log = LogManager.getLogger(AppProperties.class);
    private static final Properties PROPS = load();

    private AppProperties() {
    }

    public static String get(String key) {
	return get(key, null);
    }

    public static String get(String key, String defaultValue) {
	String fromSys = System.getProperty(key);
	if (fromSys != null && !fromSys.isBlank()) {
	    return fromSys.trim();
	}
	String fromEnv = System.getenv(key.replace('.', '_').toUpperCase());
	if (fromEnv != null && !fromEnv.isBlank()) {
	    return fromEnv.trim();
	}
	String v = PROPS.getProperty(key);
	if (v == null || v.isBlank()) {
	    return defaultValue;
	}
	return v.trim();
    }

    public static String require(String key) {
	String v = get(key, null);
	if (v == null || v.isBlank()) {
	    throw new IllegalStateException("Missing required property: " + key
		    + " (set in application.properties or active profile)");
	}
	return v;
    }

    /** Login URL from {@code zotec.portal.URL} (trailing slash ensured). */
    public static String zotecPortalUrl() {
	String v = require("zotec.portal.URL");
	return v.endsWith("/") ? v : v + "/";
    }

    /** Origin only (no path/trailing slash) for cache clear. */
    public static String zotecPortalOrigin() {
	String v = zotecPortalUrl();
	while (v.endsWith("/")) {
	    v = v.substring(0, v.length() - 1);
	}
	return v;
    }

    public static String zotecPortalUsername() {
	return require("zotec.portal.username");
    }

    public static String zotecPortalPassword() {
	return require("zotec.portal.password");
    }

    private static Properties load() {
	Properties props = new Properties();
	loadInto(props, "application.properties");
	String profile = firstNonBlank(System.getProperty("spring.profiles.active"),
		props.getProperty("spring.profiles.active"));
	if (profile != null && !profile.isBlank()) {
	    for (String p : profile.split(",")) {
		String name = p.trim();
		if (!name.isEmpty()) {
		    loadInto(props, "application-" + name + ".properties");
		}
	    }
	}
	log.info("AppProperties loaded ({} keys), profile={}", props.size(), profile);
	return props;
    }

    private static void loadInto(Properties props, String resource) {
	try (InputStream in = AppProperties.class.getClassLoader().getResourceAsStream(resource)) {
	    if (in == null) {
		log.debug("Classpath resource not found: {}", resource);
		return;
	    }
	    props.load(in);
	} catch (Exception e) {
	    log.warn("Failed to load {}: {}", resource, e.getMessage());
	}
    }

    private static String firstNonBlank(String a, String b) {
	if (a != null && !a.isBlank()) {
	    return a.trim();
	}
	if (b != null && !b.isBlank()) {
	    return b.trim();
	}
	return null;
    }
}
