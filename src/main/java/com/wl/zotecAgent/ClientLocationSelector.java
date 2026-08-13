package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.wl.util.PlaywrightService;

/**
 * Select client(s) helper: walk location checkboxes that show a report-count badge
 * ({@code badge-info} = blue / text, {@code badge-warning} = image), one at a time.
 */
final class ClientLocationSelector {

    private static final Logger log = LoggerFactory.getLogger(ClientLocationSelector.class);

    /** Blue badge — locations with non-image reports ({@code count > 0 && !isImage}). */
    static final String BADGE_INFO = "badge-info";
    /** Orange/yellow badge — locations with image reports ({@code count > 0 && isImage}). */
    static final String BADGE_WARNING = "badge-warning";

    private ClientLocationSelector() {}

    /** XPath: location checkbox immediately before a visible badge of the given kind. */
    static String checkboxXpath(String badgeKind) {
	return "//*[@class='badge " + badgeKind + " pull-right ng-binding']/preceding-sibling::input";
    }

    /**
     * Snapshot checkbox {@code id}s (or stable labels) for every location currently showing
     * the given badge. Order follows DOM order; duplicates removed.
     */
    static List<String> collectLocationKeys(Page page, String badgeKind) {
	String xpath = checkboxXpath(badgeKind);
	Locator boxes = page.locator(xpath);
	int n = boxes.count();
	Set<String> keys = new LinkedHashSet<>();
	List<String> ordered = new ArrayList<>();
	for (int i = 0; i < n; i++) {
	    Locator box = boxes.nth(i);
	    try {
		if (!box.isVisible()) {
		    continue;
		}
		String key = locationKey(box);
		if (key == null || key.isBlank() || !keys.add(key)) {
		    continue;
		}
		ordered.add(key);
	    } catch (Exception e) {
		log.warn("Skipping location checkbox [{}]: {}", i, e.getMessage());
	    }
	}
	return ordered;
    }

    /**
     * Open Select client(s), uncheck all badge-matching locations, check {@code locationKey}, APPLY.
     *
     * @return display name for upload {@code client_location}
     */
    static String selectOnlyAndApply(PlaywrightService ps, Page page, String badgeKind, String locationKey)
	    throws InterruptedException {
	openClientSelector(ps, page, badgeKind);

	String xpath = checkboxXpath(badgeKind);
	List<Locator> boxes = ps.getElements(xpath, "refresh location checkboxes (" + badgeKind + ")");

	for (Locator box : boxes) {
	    try {
		if (box.isChecked()) {
		    box.click();
		    Thread.sleep(200);
		}
	    } catch (Exception e) {
		log.warn("Could not uncheck location checkbox: {}", e.getMessage());
	    }
	}

	Locator selected = findByKey(page, xpath, locationKey);
	if (selected == null) {
	    throw new IllegalStateException(
		    "Location checkbox not found after reopen for key=" + locationKey);
	}
	String clientLocation = WorkfileSummaryScraper.readSelectedClientDisplayName(selected);
	if (!selected.isChecked()) {
	    selected.click();
	}
	Thread.sleep(2000);
	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("APPLY")),
		"APPLY location filter");
	Thread.sleep(3000);
	return clientLocation;
    }

    /**
     * Re-open Select client(s) and uncheck the finished location (and any other badge matches).
     * Call after patients for that location are done, before selecting the next.
     */
    static void uncheckAllAndApply(PlaywrightService ps, Page page, String badgeKind)
	    throws InterruptedException {
	openClientSelector(ps, page, badgeKind);
	String xpath = checkboxXpath(badgeKind);
	List<Locator> boxes = ps.getElements(xpath, "uncheck location checkboxes (" + badgeKind + ")");
	boolean anyUnchecked = false;
	for (Locator box : boxes) {
	    try {
		if (box.isChecked()) {
		    box.click();
		    anyUnchecked = true;
		    Thread.sleep(200);
		}
	    } catch (Exception e) {
		log.warn("Could not uncheck location checkbox: {}", e.getMessage());
	    }
	}
	if (anyUnchecked) {
	    ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("APPLY")),
		    "APPLY after uncheck");
	    Thread.sleep(2000);
	} else {
	    // Close dropdown without changing filter
	    Locator cancel = page.locator("#myDropdown button.zp-location-cancel-button");
	    if (cancel.count() > 0 && cancel.first().isVisible()) {
		cancel.first().click();
		Thread.sleep(500);
	    }
	}
    }

    static void openClientSelector(PlaywrightService ps, Page page, String badgeKind)
	    throws InterruptedException {
	Thread.sleep(1000);
	Locator link = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Select client(s)"));

	ps.click(link, "opening client options (click 1)");
	Thread.sleep(2000);

	if (!checkboxesVisible(page, badgeKind)) {
	    log.info("Location checkboxes not visible after first Select client(s) click — clicking again");
	    ps.click(link, "opening client options (click 2 — re-open)");
	    Thread.sleep(2000);
	}

	Locator first = page.locator(checkboxXpath(badgeKind)).first();
	if (first.count() == 0) {
	    log.warn("No {} location checkboxes visible in Select client(s)", badgeKind);
	    return;
	}
	ps.waitForElement(first, "waiting for " + badgeKind + " location checkboxes");
	Thread.sleep(1000);
    }

    private static boolean checkboxesVisible(Page page, String badgeKind) {
	try {
	    Locator first = page.locator(checkboxXpath(badgeKind)).first();
	    return first.count() > 0 && first.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private static Locator findByKey(Page page, String xpath, String locationKey) {
	Locator boxes = page.locator(xpath);
	int n = boxes.count();
	for (int i = 0; i < n; i++) {
	    Locator box = boxes.nth(i);
	    try {
		if (locationKey.equals(locationKey(box))) {
		    return box;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    /** Prefer checkbox id; fall back to cleaned display label. */
    private static String locationKey(Locator checkbox) {
	try {
	    String id = checkbox.getAttribute("id");
	    if (id != null && !id.isBlank()) {
		return id.trim();
	    }
	} catch (Exception ignored) {
	}
	return WorkfileSummaryScraper.readSelectedClientDisplayName(checkbox);
    }
}
