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
import com.microsoft.playwright.options.WaitForSelectorState;
import com.wl.util.PlaywrightService;

/**
 * Select client(s) helper: walk location checkboxes that show a report-count badge
 * ({@code badge-info} = blue / text, {@code badge-warning} = image), one at a time.
 * <p>
 * When the ZTEC Chrome extension keeps the dropdown closed, prefers a real toggle
 * click first, then force-opens Bootstrap ({@code li.dropdown.open} / {@code #myDropdown})
 * as a fallback. After Apply, force-closes so the panel is not stuck open.
 */
final class ClientLocationSelector {

    private static final Logger log = LoggerFactory.getLogger(ClientLocationSelector.class);

    /** Blue badge — locations with non-image reports ({@code count > 0 && !isImage}). */
    static final String BADGE_INFO = "badge-info";
    /** Orange/yellow badge — locations with image reports ({@code count > 0 && isImage}). */
    static final String BADGE_WARNING = "badge-warning";

    /** Bootstrap/Angular toggle: {@code <a class="dropdown-toggle" ng-click="refreshLocationFilter()">}. */
    private static final String SELECT_CLIENTS_TOGGLE =
	    "a.dropdown-toggle[ng-click*='refreshLocationFilter']";
    /** Dropdown panel shown when {@code li.dropdown} has class {@code open}. */
    private static final String CLIENT_DROPDOWN_OPEN = "li.dropdown.open #myDropdown";

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
	Thread.sleep(500);
	// After Apply: clear force-open leftovers so the panel does not stay stuck open
	forceCloseClientDropdown(page);
	Thread.sleep(500);
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
	    Thread.sleep(500);
	    forceCloseClientDropdown(page);
	    Thread.sleep(500);
	} else {
	    // Close dropdown without changing filter
	    Locator cancel = page.locator("#myDropdown button.zp-location-cancel-button");
	    if (cancel.count() > 0 && cancel.first().isVisible()) {
		cancel.first().click();
		Thread.sleep(500);
	    }
	    forceCloseClientDropdown(page);
	}
    }

    /**
     * Opens Select client(s). Prefers a real toggle click (Angular
     * {@code refreshLocationFilter}); force-opens Bootstrap state only if ZTEC
     * blocks the click path. If already open/stuck open, force-closes first so
     * we never click the toggle while force-stuck open.
     */
    static void openClientSelector(PlaywrightService ps, Page page, String badgeKind)
	    throws InterruptedException {
	page.bringToFront();
	Thread.sleep(500);

	Locator toggle = page.locator(SELECT_CLIENTS_TOGGLE).first();
	if (toggle.count() == 0) {
	    // Fallback for older DOM: role link by name
	    Locator link = page.getByRole(AriaRole.LINK,
		    new Page.GetByRoleOptions().setName("Select client(s)"));
	    link.waitFor(new Locator.WaitForOptions()
		    .setState(WaitForSelectorState.VISIBLE)
		    .setTimeout(30_000));
	    toggle = link.first();
	} else {
	    toggle.waitFor(new Locator.WaitForOptions()
		    .setState(WaitForSelectorState.VISIBLE)
		    .setTimeout(30_000));
	}
	logSelectClientsToggleEnabledState(toggle);

	// Before opening again: if already open (incl. force-stuck), close first —
	// do not click the toggle while stuck open (would fight Bootstrap / hit ACL).
	if (isClientDropdownOpen(page)) {
	    log.info("Select client(s) already open — force-closing before a clean open");
	    forceCloseClientDropdown(page);
	    Thread.sleep(300);
	}

	boolean opened = false;
	for (int attempt = 1; attempt <= 3 && !opened; attempt++) {
	    log.info("Clicking Select client(s) dropdown-toggle (attempt {})", attempt);
	    toggle.scrollIntoViewIfNeeded();
	    try {
		// Prefer real click so Angular ng-click="refreshLocationFilter()" runs
		toggle.click(new Locator.ClickOptions().setTimeout(10_000));
	    } catch (Exception e) {
		log.warn("Normal click failed ({}) — force click", e.getMessage());
		toggle.click(new Locator.ClickOptions().setForce(true).setTimeout(10_000));
	    }

	    opened = waitForClientDropdownOpen(page, 3_000);
	    if (!opened) {
		Object js = page.evaluate("() => {"
			+ "  const a = document.querySelector(\"a.dropdown-toggle[ng-click*='refreshLocationFilter']\");"
			+ "  if (!a) return 'missing';"
			+ "  a.click();"
			+ "  return 'clicked';"
			+ "}");
		log.info("JS toggle click result={} (attempt {})", js, attempt);
		opened = waitForClientDropdownOpen(page, 2_000);
	    }
	    if (!opened) {
		// ZTEC fallback only: force Bootstrap open without relying on the click path
		Object forced = forceOpenClientDropdown(page);
		log.info("Force-open Select client(s) result={} (attempt {})", forced, attempt);
		opened = waitForClientDropdownOpen(page, 3_000);
	    }
	    if (!opened && attempt < 3) {
		Thread.sleep(1000);
	    }
	}
	if (!opened) {
	    throw new IllegalStateException(
		    "Select client(s) dropdown did not open (#myDropdown / li.dropdown.open)");
	}
	log.info("Select client(s) dropdown is open (#myDropdown visible)");

	waitForClientCheckboxesWithForceOpen(ps, page, badgeKind);
	Thread.sleep(500);
    }

    /**
     * Force Bootstrap dropdown open for ZTEC interference: {@code li.dropdown.open},
     * show {@code #myDropdown}, and invoke Angular {@code refreshLocationFilter} when available.
     */
    private static Object forceOpenClientDropdown(Page page) {
	return page.evaluate("() => {"
		+ "  const a = document.querySelector(\"a.dropdown-toggle[ng-click*='refreshLocationFilter']\");"
		+ "  if (!a) return 'missing-toggle';"
		+ "  const li = a.closest('li.dropdown');"
		+ "  if (!li) return 'missing-li';"
		+ "  const panel = document.getElementById('myDropdown') || li.querySelector('#myDropdown');"
		+ "  if (!panel) return 'missing-panel';"
		+ "  try {"
		+ "    if (window.angular) {"
		+ "      const el = window.angular.element(a);"
		+ "      const scope = el.scope && el.scope();"
		+ "      if (scope && typeof scope.refreshLocationFilter === 'function') {"
		+ "        if (scope.$apply) {"
		+ "          scope.$apply(function() { scope.refreshLocationFilter(); });"
		+ "        } else {"
		+ "          scope.refreshLocationFilter();"
		+ "        }"
		+ "      }"
		+ "    }"
		+ "  } catch (e) { /* Angular may be unavailable */ }"
		+ "  li.classList.add('open');"
		+ "  a.setAttribute('aria-expanded', 'true');"
		+ "  panel.style.display = 'block';"
		+ "  panel.style.visibility = 'visible';"
		+ "  if (panel.classList) panel.classList.add('open');"
		+ "  return 'forced-open';"
		+ "}");
    }

    /**
     * Force-close after Apply / before re-open: remove {@code open}, clear
     * {@code #myDropdown} inline display/visibility, set {@code aria-expanded=false}.
     */
    private static Object forceCloseClientDropdown(Page page) {
	Object result = page.evaluate("() => {"
		+ "  const a = document.querySelector(\"a.dropdown-toggle[ng-click*='refreshLocationFilter']\");"
		+ "  const li = a ? a.closest('li.dropdown') : document.querySelector('li.dropdown:has(#myDropdown)');"
		+ "  const panel = document.getElementById('myDropdown')"
		+ "      || (li && li.querySelector('#myDropdown'));"
		+ "  if (li) li.classList.remove('open');"
		+ "  if (a) a.setAttribute('aria-expanded', 'false');"
		+ "  if (panel) {"
		+ "    panel.style.display = 'none';"
		+ "    panel.style.visibility = 'hidden';"
		+ "    if (panel.classList) panel.classList.remove('open');"
		+ "  }"
		+ "  return panel ? 'forced-closed' : 'missing-panel';"
		+ "}");
	log.info("Force-close Select client(s) result={}", result);
	return result;
    }

    /**
     * Wait for client checkboxes; if ZTEC closes the panel after force-open, re-force and retry.
     */
    private static void waitForClientCheckboxesWithForceOpen(PlaywrightService ps, Page page,
	    String badgeKind) throws InterruptedException {
	String xpath = checkboxXpath(badgeKind);
	for (int attempt = 1; attempt <= 5; attempt++) {
	    if (!isClientDropdownOpen(page)) {
		Object forced = forceOpenClientDropdown(page);
		log.info("Re-force-open Select client(s) before checkbox wait (attempt {}) result={}",
			attempt, forced);
		Thread.sleep(300);
	    }
	    try {
		ps.waitForElement(page.locator(xpath).first(),
			"waiting for " + badgeKind + " location checkboxes");
		if (checkboxesVisible(page, badgeKind)) {
		    return;
		}
	    } catch (Exception e) {
		log.warn("Client checkboxes not ready (attempt {}): {}", attempt, e.getMessage());
	    }
	    forceOpenClientDropdown(page);
	    Thread.sleep(500);
	}
	ps.waitForElement(page.locator(xpath).first(), "waiting for " + badgeKind + " location checkboxes");
    }

    /**
     * Logs whether the Select client(s) dropdown toggle is enabled or disabled
     * (Playwright {@code isEnabled}/{@code isDisabled}, plus {@code disabled} /
     * {@code aria-disabled} / CSS {@code disabled} class).
     */
    private static void logSelectClientsToggleEnabledState(Locator toggle) {
	try {
	    boolean playwrightEnabled = toggle.isEnabled();
	    boolean playwrightDisabled = toggle.isDisabled();
	    String disabledAttr = toggle.getAttribute("disabled");
	    String ariaDisabled = toggle.getAttribute("aria-disabled");
	    String cls = toggle.getAttribute("class");
	    boolean classDisabled = cls != null && cls.toLowerCase().contains("disabled");
	    boolean attrDisabled = disabledAttr != null;
	    boolean ariaIsDisabled = "true".equalsIgnoreCase(ariaDisabled);
	    boolean effectivelyDisabled = playwrightDisabled || attrDisabled || ariaIsDisabled
		    || classDisabled;
	    String state = effectivelyDisabled ? "DISABLED" : "ENABLED";
	    log.info(
		    "Select client(s) dropdown button is {} (playwrightEnabled={}, playwrightDisabled={},"
			    + " disabledAttr={}, aria-disabled={}, classDisabled={}, class='{}')",
		    state, playwrightEnabled, playwrightDisabled, disabledAttr, ariaDisabled,
		    classDisabled, cls);
	} catch (Exception e) {
	    log.warn("Could not read Select client(s) dropdown enabled/disabled state: {}",
		    e.getMessage());
	}
    }

    private static boolean waitForClientDropdownOpen(Page page, double timeoutMs) {
	try {
	    page.locator(CLIENT_DROPDOWN_OPEN).first()
		    .waitFor(new Locator.WaitForOptions()
			    .setState(WaitForSelectorState.VISIBLE)
			    .setTimeout(timeoutMs));
	    return true;
	} catch (Exception e) {
	    return isClientDropdownOpen(page);
	}
    }

    private static boolean isClientDropdownOpen(Page page) {
	try {
	    Locator open = page.locator(CLIENT_DROPDOWN_OPEN).first();
	    if (open.count() > 0 && open.isVisible()) {
		return true;
	    }
	    Locator panel = page.locator("#myDropdown").first();
	    return panel.count() > 0 && panel.isVisible();
	} catch (Exception e) {
	    return false;
	}
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
