package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.wl.util.AppProperties;
import com.wl.util.PlaywrightService;

public class Service {
    public static final Logger logger = LogManager.getLogger(Service.class);

    private static final String CPT_ROWS =
	    "//*[@ng-controller='Coding.Form.Coding.Professional.Charges.RowController' and @ng-form='rowForm']";
    private static final String ICD_ROWS =
	    "//*[@ng-controller='Coding.Form.Coding.Professional.Diagnoses.RowController']";

    void login(Page page) throws Exception {

	PlaywrightService ps = new PlaywrightService(page);

	String portalUrl = AppProperties.zotecPortalUrl();
	String username = AppProperties.zotecPortalUsername();
	String password = AppProperties.zotecPortalPassword();

	page.navigate(portalUrl);
	ps.fill(page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("E-Mail Address")),
		username, "filling user name");

	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next")), "clicing the next");
	ps.fill(page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")), password,
		"filling password");
	ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Verify")), "clicking verify");
	Thread.sleep(2000);
	Page page1 = page.waitForPopup(() -> {
	    page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("launch app Coding Workfile")).click();
	});
//  	page1.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Select client(s)")).click();
//  	page1.locator("#checkbox-CFV1-CVF1BJH").check();
//  	

    }

    void clientsOperation(Page page) {
	PlaywrightService ps = new PlaywrightService(page);
	ps.click(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Select client(s)")),
		"clicking client options");
	List<Locator> clients = ps.getElements(
		"//*[@class='badge badge-info pull-right ng-binding']/preceding-sibling::input", "getting loc");

	for (int x = 0; x < 1; x++) {

	    clients.get(x).click();

	    ps.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("APPLY")), "clickiing ");

	    break;
	}
    }

    /** Backward-compatible: codes only (no modifier/units/diagnoses). */
    void validateCPT(Page page, Set<String> expectedValues) throws InterruptedException {
	List<Map<String, Object>> entries = new ArrayList<>();
	for (String code : expectedValues) {
	    if (code == null || code.isBlank()) {
		continue;
	    }
	    Map<String, Object> entry = new LinkedHashMap<>();
	    entry.put("code", code.trim());
	    entries.add(entry);
	}
	validateCPT(page, entries, List.of());
    }

    /**
     * Clears every existing CPT charge row, then fills CPT rows from JSON in order
     * (modifier / units / diagnosis pointers / servicelocation / pos). Description is
     * left to Zotec (auto-set when code is chosen).
     * <p>
     * Strategy: delete-all then refill so UI matches JSON (no leftover blank or
     * stale CPT rows).
     *
     * @param cptEntries ordered CPT maps with {@code code}, optional {@code modifier},
     *                   {@code units}, {@code diagnoses}, {@code servicelocation}, {@code pos}
     * @param icdOrder   ICD codes in UI/JSON order (used to map diagnoses codes → 1-based pointers)
     */
    void validateCPT(Page page, List<Map<String, Object>> cptEntries, List<String> icdOrder)
	    throws InterruptedException {
	Set<String> expectedNormalized = new HashSet<>();
	for (Map<String, Object> entry : cptEntries) {
	    String code = str(entry, "code");
	    if (code != null) {
		expectedNormalized.add(normalizeCptCode(code));
	    }
	}
	PlayTestActionLog.step("validateCPT — expected: " + expectedNormalized);

	Locator rows = page.locator(CPT_ROWS);
	rows.first().waitFor();

	Set<String> uiValues = collectCptUiCodes(page);
	logger.info("CPT UI values before sync: {}", uiValues);

	// Delete ALL existing CPT rows (filled + blank removable), then refill from JSON
	PlayTestActionLog.step("CPT — delete all existing rows, then fill from JSON");
	deleteAllCptRows(page);
	uiValues = collectCptUiCodes(page);
	logger.info("CPT UI values after delete-all: {}", uiValues);

	for (Map<String, Object> entry : cptEntries) {
	    String expected = str(entry, "code");
	    if (expected == null) {
		continue;
	    }
	    String norm = normalizeCptCode(expected);
	    PlayTestActionLog.add("CPT", expected);
	    Locator tempRow = page.locator(CPT_ROWS).last();
	    boolean ok = select2TypeAndChoose(page, tempRow, expected, "CPT " + expected);
	    if (ok) {
		uiValues.add(norm);
		Thread.sleep(300);
	    } else {
		PlayTestActionLog.skip("CPT " + expected, "select2 did not commit");
	    }
	}

	// Safety: remove any leftover codes not in JSON, and any blank removable rows
	deleteUnwantedCptRows(page, expectedNormalized);
	deleteBlankRemovableCptRows(page);
	uiValues = collectCptUiCodes(page);
	logger.info("CPT UI values after final sync: {}", uiValues);
	if (!uiValues.equals(expectedNormalized)) {
	    logger.warn("CPT UI still differs from JSON. UI={} JSON={}", uiValues, expectedNormalized);
	}

	String formServiceLocationApplied = null;
	boolean serviceLocationAvailable = isServiceLocationAvailable(page);
	boolean placeOfServiceAvailable = isPlaceOfServiceAvailable(page);
	boolean loggedSlSkip = false;
	boolean loggedPosSkip = false;

	for (Map<String, Object> entry : cptEntries) {
	    String code = str(entry, "code");
	    if (code == null) {
		continue;
	    }
	    Locator row = findCptRow(page, code);
	    if (row == null) {
		PlayTestActionLog.skip("CPT details " + code, "row not found");
		continue;
	    }

	    // Fixed order per CPT: code (already synced) → modifiers → units → diagnoses → SL → POS
	    logger.info("CPT fill order for {}: modifiers → units → diagnoses → Service Location → POS", code);
	    fillCptRowDetails(page, row, entry, icdOrder); // modifiers, units, diagnoses only

	    String rowServiceLoc = firstNonBlank(str(entry, "servicelocation"), str(entry, "service_location"));
	    if (rowServiceLoc != null && !rowServiceLoc.isBlank()) {
		if (!serviceLocationAvailable) {
		    if (!loggedSlSkip) {
			logger.info(
				"Service Location skipped — not on form (non-ED / RAD coding form)");
			PlayTestActionLog.skip("Service Location (form)", "field not on form (non-ED / RAD)");
			loggedSlSkip = true;
		    }
		} else {
		    applyCptServiceLocation(page, row, code, rowServiceLoc);
		    if (formServiceLocationApplied == null
			    || !formServiceLocationApplied.equalsIgnoreCase(rowServiceLoc.trim())) {
			if (applyFormServiceLocation(page, rowServiceLoc)) {
			    formServiceLocationApplied = rowServiceLoc.trim();
			    logger.info("Form Service Location set to '{}' for CPT {}", formServiceLocationApplied,
				    code);
			}
		    }
		    Thread.sleep(250); // brief settle after SL before POS
		}
	    }

	    String rowPos = firstNonBlank(str(entry, "pos"), str(entry, "pos_code"), str(entry, "place_of_service"));
	    if (rowPos != null && !rowPos.isBlank()) {
		if (!placeOfServiceAvailable) {
		    if (!loggedPosSkip) {
			logger.info("POS '{}' skipped — not on form (non-ED / RAD coding form)", rowPos);
			PlayTestActionLog.skip("POS (form)", "field not on form (non-ED / RAD)");
			loggedPosSkip = true;
		    }
		} else {
		    logger.info("Applying POS '{}' last for CPT {}", rowPos, code);
		    ensureChargeLocationPanelOpen(page, row);
		    if (!applyFormPlaceOfService(page, rowPos)) {
			logger.warn("POS '{}' not applied after CPT {} — retrying once", rowPos, code);
			Thread.sleep(300);
			ensureChargeLocationPanelOpen(page, row);
			applyFormPlaceOfService(page, rowPos);
		    }
		}
	    }
	}

	if (placeOfServiceAvailable) {
	    for (Map<String, Object> entry : cptEntries) {
		String rowPos = firstNonBlank(str(entry, "pos"), str(entry, "pos_code"), str(entry, "place_of_service"));
		if (rowPos != null && !rowPos.isBlank()) {
		    logger.info("Final POS ensure after all CPT fills: '{}'", rowPos);
		    ensureChargeLocationPanelOpen(page, null);
		    applyFormPlaceOfService(page, rowPos);
		    break;
		}
	    }
	}
    }

    /**
     * Order: modifiers → units → diagnoses. Service Location and POS are applied by the caller after this.
     * Description is auto-set by Zotec when CPT code is chosen — never filled from JSON.
     */
    private void fillCptRowDetails(Page page, Locator row, Map<String, Object> entry, List<String> icdOrder) {
	String code = str(entry, "code");
	String modifier = str(entry, "modifier");
	String diagnosesRaw = str(entry, "diagnoses");
	String diagnosesPointers = toDiagnosisPointers(diagnosesRaw, icdOrder);

	FieldBundle fields = resolveCptDetailFields(row);

	// 1) Modifiers
	if (modifier != null && !modifier.isBlank()) {
	    setModifierOnRow(page, row, fields.modifier, modifier, code);
	} else {
	    clearModifierChips(row);
	}

	// 2) Units
	if (entry.containsKey("units")) {
	    String units = unitsToString(entry.get("units"));
	    if (fields.units != null) {
		if (units != null && !units.isBlank()) {
		    setInputForce(page, fields.units, units, "units for " + code);
		} else {
		    setInputForce(page, fields.units, "1", "units for " + code + " (null→default 1)");
		}
	    } else {
		PlayTestActionLog.skip("units for " + code, "units input not found on row");
		logger.warn("units input not found for CPT {}", code);
	    }
	}

	// 3) Diagnoses
	if (diagnosesPointers != null && !diagnosesPointers.isBlank()) {
	    if (fields.diagnoses != null) {
		setInputForce(page, fields.diagnoses, diagnosesPointers, "diagnoses for " + code);
	    } else {
		PlayTestActionLog.skip("diagnoses for " + code, "diagnoses input not found on row");
	    }
	}
    }

    /** Row-level Service Location when present on the charge. */
    private void applyCptServiceLocation(Page page, Locator row, String code, String serviceLocation) {
	if (serviceLocation == null || serviceLocation.isBlank()) {
	    return;
	}
	FieldBundle fields = resolveCptDetailFields(row);
	if (fields.serviceLocation != null) {
	    setInputForce(page, fields.serviceLocation, serviceLocation, "servicelocation for " + code);
	    return;
	}
	if (!setSelect2InRow(page, row, serviceLocation, "service", "servicelocation for " + code)
		&& !setSelect2InRow(page, row, serviceLocation, "location", "servicelocation for " + code)) {
	    PlayTestActionLog.skip("servicelocation for " + code, "no row field; will try form-level");
	}
    }

    /** Opens charge Location/POC panel so charge POS Select2 is in the DOM. */
    private void ensureChargeLocationPanelOpen(Page page, Locator cptRow) {
	try {
	    Locator posAlready = findPosContainer(page);
	    if (posAlready != null) {
		return;
	    }
	    Locator scope = cptRow != null ? cptRow.locator("xpath=ancestor::table[1]") : page.locator("body");
	    Locator btn = scope.locator(
		    "button[ng-click*='showHideLocation'], button[title*='Location'], button[title*='POC']")
		    .first();
	    if (btn.count() == 0) {
		btn = page.locator(
			"button[ng-click*='showHideLocation'], button[title*='Use default Location']").first();
	    }
	    if (btn.count() > 0 && btn.isVisible()) {
		logger.info("Opening charge Location/POC panel so POS is available");
		btn.click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(300);
	    }
	} catch (Exception e) {
	    logger.warn("Could not open Location/POC panel: {}", e.getMessage());
	}
    }

    /** True when header Service Location Select2 is visible on the form. */
    private boolean isServiceLocationAvailable(Page page) {
	try {
	    Locator choice = page.locator("#s2id_serviceLocation .select2-choice").first();
	    if (choice.count() > 0 && choice.isVisible()) {
		return true;
	    }
	    Locator byLabel = page.locator(
		    "xpath=//label[contains(text(),'Service Location')]/following::div[contains(@class,'select2-container')][1]//a[contains(@class,'select2-choice')]")
		    .first();
	    return byLabel.count() > 0 && byLabel.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    /**
     * True when POS Select2 is in the DOM, or an ED Location/POC button can reveal it.
     * RAD / non-ED professional forms typically return false.
     */
    private boolean isPlaceOfServiceAvailable(Page page) {
	if (findPosContainer(page) != null) {
	    return true;
	}
	try {
	    Locator btn = page.locator(
		    "button[ng-click*='showHideLocation'], button[title*='Location'], button[title*='POC']")
		    .first();
	    return btn.count() > 0 && btn.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private static final class FieldBundle {
	Locator modifier;
	Locator units;
	Locator diagnoses;
	Locator description;
	Locator serviceLocation;
    }

    /** Resolve CPT detail inputs; fill missing ones individually from visible text inputs. */
    private FieldBundle resolveCptDetailFields(Locator row) {
	FieldBundle b = new FieldBundle();
	b.modifier = findRowField(row, "modifier");
	b.units = firstPresent(findRowField(row, "units"), findRowField(row, "unit"));
	b.diagnoses = findRowField(row, "diagnos");
	b.description = firstPresent(findRowField(row, "description"), findRowField(row, "desc"));
	b.serviceLocation = firstPresent(findRowField(row, "servicelocation"), findRowField(row, "service_location"),
		findRowField(row, "serviceLocation"), findRowField(row, "location"));

	List<Locator> fallbacks = visibleTextInputs(row);
	List<Locator> unclassified = new ArrayList<>();
	for (Locator f : fallbacks) {
	    String blob = fieldBlob(f);
	    if (b.modifier == null && blob.contains("mod")) {
		b.modifier = f;
	    } else if (b.units == null && blob.contains("unit")) {
		b.units = f;
	    } else if (b.diagnoses == null && blob.contains("diagnos")) {
		b.diagnoses = f;
	    } else if (f != b.modifier && f != b.units && f != b.diagnoses) {
		unclassified.add(f);
	    }
	}

	// Positional leftovers: typically modifiers → units → diagnoses (left to right)
	int i = 0;
	if (b.modifier == null && i < unclassified.size()) {
	    b.modifier = unclassified.get(i++);
	}
	if (b.units == null && i < unclassified.size()) {
	    b.units = unclassified.get(i++);
	}
	if (b.diagnoses == null && i < unclassified.size()) {
	    b.diagnoses = unclassified.get(i);
	}

	if (b.modifier == null) {
	    b.modifier = firstPresent(inputInTd(row, 4), inputInTd(row, 3));
	}
	if (b.units == null) {
	    b.units = firstPresent(inputInTd(row, 5), inputInTd(row, 4));
	}
	if (b.diagnoses == null) {
	    b.diagnoses = firstPresent(inputInTd(row, 6), inputInTd(row, 5));
	}
	return b;
    }

    private Locator inputInTd(Locator row, int nthChild) {
	try {
	    Locator cell = row.locator("td:nth-child(" + nthChild + ")");
	    if (cell.count() == 0) {
		return null;
	    }
	    Locator input = cell.locator("input[type='text'], input:not([type]), input.form-control, textarea")
		    .first();
	    if (input.count() > 0 && input.isVisible()) {
		String cls = safeAttr(input, "class");
		if (!cls.contains("select2-input")) {
		    return input;
		}
	    }
	} catch (Exception ignored) {
	}
	return null;
    }

    private String fieldBlob(Locator loc) {
	return (safeAttr(loc, "ng-model") + " " + safeAttr(loc, "name") + " " + safeAttr(loc, "id") + " "
		+ safeAttr(loc, "class") + " " + safeAttr(loc, "placeholder")).toLowerCase(Locale.ROOT);
    }

    /**
     * Fills Modifiers for a CPT row.
     * <p>
     * Zotec control (from UI HTML): multi Select2
     * {@code #s2id_modifiertype} / {@code span[type=modifiers]} /
     * hidden {@code input#modifiertype[name=modifiers]}.
     * <p>
     * JSON may send one or many codes in one string, e.g. {@code "25,GC"} — each
     * code is added to the multi-Select2 one after another.
     */
    private void setModifierOnRow(Page page, Locator row, Locator ignoredModifierInput, String modifier,
	    String code) {
	String desc = "modifier for " + code;
	try {
	    List<String> wanted = splitModifiers(modifier);
	    if (wanted.isEmpty()) {
		clearModifierChips(row);
		return;
	    }

	    Locator modContainer = findModifiersSelect2(row);
	    if (modContainer == null) {
		PlayTestActionLog.skip(desc, "s2id_modifiertype / type=modifiers not on row");
		logger.warn("Modifiers Select2 not found for CPT {}", code);
		return;
	    }

	    String current = readModifierDisplay(row);
	    if (modifierEquals(current, modifier)) {
		PlayTestActionLog.skip(desc, current);
		return;
	    }

	    if (current != null && !current.isBlank()) {
		PlayTestActionLog.update(desc, "clearing '" + current + "' before '" + String.join(",", wanted) + "'");
		clearModifierChips(row);
		dismissSelect2(page);
	    }

	    boolean allOk = true;
	    for (String one : wanted) {
		logger.info("Adding CPT {} modifier '{}' (of {})", code, one, wanted);
		PlayTestActionLog.update(desc, "add '" + one + "'");
		modContainer = findModifiersSelect2(row);
		if (modContainer == null) {
		    allOk = false;
		    break;
		}
		if (!setModifiersMultiSelect2(page, row, modContainer, one, desc + " [" + one + "]")) {
		    logger.warn("Could not add modifier '{}' for CPT {}", one, code);
		    allOk = false;
		}
		dismissSelect2(page);
		Thread.sleep(200);
	    }

	    String after = readModifierDisplay(row);
	    if (modifierEquals(after, modifier)) {
		PlayTestActionLog.update(desc, "set to '" + after + "'");
		return;
	    }
	    if (allOk) {
		logger.warn("modifier for CPT {}: expected '{}' display now '{}'", code, modifier, after);
	    }
	    PlayTestActionLog.skip(desc, "wanted '" + modifier + "' got '" + after + "'");
	} catch (Exception e) {
	    logger.warn("setModifierOnRow failed for {}: {}", code, e.getMessage());
	    PlayTestActionLog.skip(desc, e.getMessage());
	}
    }

    /** Splits {@code "25,GC"} / {@code "25; GC"} into individual modifier codes (order preserved). */
    static List<String> splitModifiers(String raw) {
	List<String> out = new ArrayList<>();
	if (raw == null || raw.isBlank()) {
	    return out;
	}
	Set<String> seen = new HashSet<>();
	for (String part : raw.split("[,;/|]+")) {
	    String t = part.trim();
	    if (t.isEmpty()) {
		continue;
	    }
	    String key = t.toUpperCase(Locale.ROOT);
	    if (seen.add(key)) {
		out.add(t);
	    }
	}
	return out;
    }

    /** Row-scoped Modifiers multi-Select2 container. */
    private Locator findModifiersSelect2(Locator row) {
	String[] selectors = {
		"#s2id_modifiertype",
		"span[type='modifiers'] .select2-container",
		"div[ng-controller='Controls.Editor.ModifiersController'] .select2-container",
		".select2-container-multi#s2id_modifiertype"
	};
	for (String sel : selectors) {
	    try {
		Locator loc = row.locator(sel).first();
		if (loc.count() > 0 && loc.isVisible()) {
		    return loc;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    /**
     * Types one modifier code into the multi Select2 search and picks a matching result.
     */
    private boolean setModifiersMultiSelect2(Page page, Locator row, Locator modContainer, String modifier,
	    String desc) {
	try {
	    dismissSelect2(page);
	    PlayTestActionLog.update(desc, "multi-select2 #s2id_modifiertype -> '" + modifier + "'");

	    Locator search = modContainer.locator("input.select2-input").first();
	    if (search.count() == 0 || !search.isVisible()) {
		modContainer.click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(200);
		search = modContainer.locator("input.select2-input").first();
	    }
	    if (search.count() == 0) {
		Locator fallback = visibleSelect2Search(page);
		if (fallback != null) {
		    search = fallback;
		}
	    }
	    if (search.count() == 0) {
		return false;
	    }

	    search.click(new Locator.ClickOptions().setForce(true));
	    search.fill("");
	    search.fill(modifier);
	    Thread.sleep(400);

	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, "
			    + ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");
	    for (int i = 0; i < 6 && results.count() == 0; i++) {
		if (page.locator(
			".select2-drop-active li.select2-no-results, "
				+ ".select2-drop:not(.select2-display-none) li.select2-no-results")
			.count() > 0) {
		    dismissSelect2(page);
		    return false;
		}
		Thread.sleep(250);
	    }

	    if (results.count() > 0) {
		String want = modifier.toUpperCase(Locale.ROOT);
		Locator match = results.first();
		for (int i = 0; i < results.count(); i++) {
		    String t = results.nth(i).innerText().trim().toUpperCase(Locale.ROOT);
		    if (t.equals(want) || t.startsWith(want + " ") || t.startsWith(want + "-")
			    || t.contains(want)) {
			match = results.nth(i);
			if (t.equals(want) || t.startsWith(want + " ") || t.startsWith(want + "-")) {
			    break;
			}
		    }
		}
		match.click(new Locator.ClickOptions().setForce(true));
	    } else {
		search.press("Enter");
	    }
	    Thread.sleep(350);
	    dismissSelect2(page);

	    // Success if this single code appears among chips (row may already have others)
	    return modifierDisplayContains(readModifierDisplay(row), modifier);
	} catch (Exception e) {
	    logger.warn("setModifiersMultiSelect2: {}", e.getMessage());
	    try {
		dismissSelect2(page);
	    } catch (Exception ignored) {
	    }
	    return false;
	}
    }

    private String readModifierDisplay(Locator row) {
	try {
	    Locator container = findModifiersSelect2(row);
	    Locator chips = container != null
		    ? container.locator(".select2-search-choice, .select2-selection__choice")
		    : row.locator(
			    "span[type='modifiers'] .select2-search-choice, #s2id_modifiertype .select2-search-choice");
	    StringBuilder sb = new StringBuilder();
	    for (int i = 0; i < chips.count(); i++) {
		String t = chips.nth(i).innerText().trim();
		if (!t.isEmpty()) {
		    if (sb.length() > 0) {
			sb.append(',');
		    }
		    sb.append(t.replace("×", "").trim());
		}
	    }
	    if (sb.length() > 0) {
		return sb.toString();
	    }
	    Locator hidden = row.locator("input#modifiertype, input[name='modifiers']").first();
	    if (hidden.count() > 0) {
		return safeInputValue(hidden);
	    }
	} catch (Exception ignored) {
	}
	return "";
    }

    private void clearModifierChips(Locator row) {
	try {
	    Locator container = findModifiersSelect2(row);
	    Locator scope = container != null ? container : row;
	    Locator closes = scope.locator(
		    ".select2-search-choice-close, a.select2-search-choice-close, .select2-selection__choice__remove");
	    for (int i = closes.count() - 1; i >= 0; i--) {
		try {
		    closes.nth(i).click(new Locator.ClickOptions().setForce(true).setTimeout(800));
		    Thread.sleep(100);
		} catch (Exception ignored) {
		}
	    }
	} catch (Exception ignored) {
	}
    }

    private static boolean modifierEquals(String current, String expected) {
	if (expected == null || expected.isBlank()) {
	    return current == null || current.isBlank();
	}
	if (current == null || current.isBlank()) {
	    return false;
	}
	Set<String> want = new HashSet<>();
	for (String m : splitModifiers(expected)) {
	    want.add(m.toUpperCase(Locale.ROOT));
	}
	Set<String> have = new HashSet<>();
	for (String m : splitModifiers(current)) {
	    have.add(m.toUpperCase(Locale.ROOT));
	}
	return !want.isEmpty() && have.containsAll(want);
    }

    private static boolean modifierDisplayContains(String display, String one) {
	if (one == null || one.isBlank()) {
	    return true;
	}
	if (display == null || display.isBlank()) {
	    return false;
	}
	String want = one.trim().toUpperCase(Locale.ROOT);
	for (String m : splitModifiers(display)) {
	    if (m.toUpperCase(Locale.ROOT).equals(want)
		    || m.toUpperCase(Locale.ROOT).startsWith(want + " ")
		    || m.toUpperCase(Locale.ROOT).startsWith(want)) {
		return true;
	    }
	}
	return display.replaceAll("\\s+", "").toUpperCase(Locale.ROOT).contains(want);
    }

    private static String safeInputValue(Locator input) {
	try {
	    String v = input.inputValue();
	    return v == null ? "" : v.trim();
	} catch (Exception e) {
	    return "";
	}
    }

    /** Write value into an input; skip only when already equal. */
    private void setInputForce(Page page, Locator input, String value, String desc) {
	if (input == null || value == null) {
	    PlayTestActionLog.skip(desc, "input not present");
	    return;
	}
	try {
	    String current = safeInputValue(input);
	    if (value.equalsIgnoreCase(current)) {
		PlayTestActionLog.skip(desc, current);
		return;
	    }
	    PlayTestActionLog.update(desc, "'" + current + "' -> '" + value + "'");
	    dismissSelect2(page);
	    input.click(new Locator.ClickOptions().setForce(true));
	    input.fill("");
	    input.fill(value);
	    input.press("Tab");
	    Thread.sleep(300);
	} catch (Exception e) {
	    logger.warn("Unable to set {}: {}", desc, e.getMessage());
	    PlayTestActionLog.skip(desc, e.getMessage());
	}
    }

    /** Form-level Service Location dropdown (once), when CPT rows have no per-line control. */
    private boolean applyFormServiceLocation(Page page, String serviceLocation) {
	if (serviceLocation == null || serviceLocation.isBlank()) {
	    return false;
	}
	try {
	    Locator choice = page.locator("#s2id_serviceLocation .select2-choice").first();
	    if (choice.count() == 0 || !choice.isVisible()) {
		return false;
	    }
	    String current = "";
	    try {
		current = page.locator("#s2id_serviceLocation .select2-chosen").first().innerText().trim();
	    } catch (Exception ignored) {
	    }
	    if (serviceLocation.equalsIgnoreCase(current)) {
		PlayTestActionLog.skip("Service Location (form)", current);
		return true;
	    }
	    PlayTestActionLog.update("Service Location (form)", "'" + current + "' -> '" + serviceLocation + "'");
	    choice.click();
	    Thread.sleep(200);
	    Locator search = page.locator(
		    ".select2-drop-active .select2-search input.select2-input, .select2-drop:not(.select2-display-none) .select2-search input.select2-input")
		    .first();
	    search.waitFor(new Locator.WaitForOptions()
		    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(5000));
	    search.fill(serviceLocation);
	    Thread.sleep(600);
	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, .select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");
	    if (results.count() > 0) {
		results.first().click();
		Thread.sleep(200);
		return true;
	    }
	    page.keyboard().press("Escape");
	    PlayTestActionLog.skip("Service Location (form)", "no select2 match for '" + serviceLocation + "'");
	} catch (Exception e) {
	    logger.warn("Unable to set form Service Location: {}", e.getMessage());
	}
	return false;
    }

    /**
     * Place of Service from CPT {@code pos}. Tries header #s2id_placeOfService, then
     * label-based POS, then charge override ({@code chargeplaceOfServicever}).
     * Always intended to run after Service Location.
     */
    private boolean applyFormPlaceOfService(Page page, String pos) {
	if (pos == null || pos.isBlank()) {
	    return false;
	}
	try {
	    Locator container = findPosContainer(page);
	    if (container == null) {
		PlayTestActionLog.skip("POS (form)", "field not on form");
		logger.warn("POS container not found (#s2id_placeOfService / label POS / charge POS)");
		return false;
	    }

	    String current = readFormPosChosen(container);
	    if (posMatches(current, pos)) {
		PlayTestActionLog.skip("POS (form)", current);
		return true;
	    }

	    boolean currentBlank = current == null || current.isBlank() || "\u00a0".equals(current.trim());
	    String cls = String.valueOf(container.getAttribute("class"));
	    boolean disabled = cls != null && cls.contains("select2-container-disabled");

	    // JSON wins: even if POS is disabled/auto from Service Location (e.g. UI 23, JSON 24)
	    if (disabled) {
		logger.info("POS disabled with '{}' — force-enable and set JSON '{}' (blank={})",
			current, pos, currentBlank);
		forceEnablePosContainer(page, container);
	    }

	    PlayTestActionLog.update("POS (form)", "'" + current + "' -> '" + pos + "'");
	    logger.info("Setting POS to '{}' (was '{}', disabled={})", pos, current, disabled);

	    forceEnablePosContainer(page, container);

	    if (trySelect2SetPos(page, container, pos)) {
		String after = readFormPosChosen(container);
		if (posMatches(after, pos)) {
		    logger.info("POS set via Select2 UI to '{}'", after);
		    return true;
		}
	    }

	    forceEnablePosContainer(page, container);
	    Object jsResult = setPosViaJavascript(page, pos);
	    Thread.sleep(200);
	    container = findPosContainer(page);
	    String afterJs = container != null ? readFormPosChosen(container) : "";
	    if (posMatches(afterJs, pos)) {
		logger.info("POS set via JS fallback to '{}' (result={})", afterJs, jsResult);
		return true;
	    }

	    container = findPosContainer(page);
	    if (container != null) {
		forceEnablePosContainer(page, container);
		if (trySelect2SetPos(page, container, pos)) {
		    String after2 = readFormPosChosen(container);
		    if (posMatches(after2, pos)) {
			logger.info("POS set via Select2 retry to '{}'", after2);
			return true;
		    }
		}
	    }

	    logger.warn("POS still blank/wrong after all attempts. UI='{}' wanted='{}' js={}",
		    afterJs, pos, jsResult);
	    PlayTestActionLog.skip("POS (form)", "could not set '" + pos + "' (still blank/disabled)");
	} catch (Exception e) {
	    logger.warn("Unable to set form POS: {}", e.getMessage());
	}
	return false;
    }

    /**
     * Finds POS Select2: header id, id contains placeOfService, label "POS", or charge input sibling.
     */
    private Locator findPosContainer(Page page) {
	String[] selectors = {
		"#s2id_placeOfService",
		"div.select2-container[id*='placeOfService']",
		"xpath=//label[normalize-space()='POS']/following-sibling::div[contains(@class,'select2-container')][1]",
		"xpath=//label[normalize-space()='POS']/following::div[contains(@class,'select2-container')][1]",
		"xpath=//label[contains(normalize-space(),'POS')]/following::div[contains(@class,'select2-container')][1]",
		"xpath=//input[@name='chargeplaceOfServicever']/preceding-sibling::div[contains(@class,'select2-container')][1]",
		"xpath=//input[@id='placeOfService']/preceding-sibling::div[contains(@class,'select2-container')][1]",
		"xpath=//input[@name='placeOfService']/preceding-sibling::div[contains(@class,'select2-container')][1]"
	};
	Locator fallback = null;
	for (String sel : selectors) {
	    try {
		Locator loc = page.locator(sel).first();
		if (loc.count() == 0) {
		    continue;
		}
		try {
		    if (loc.isVisible()) {
			return loc;
		    }
		} catch (Exception ignored) {
		}
		if (fallback == null) {
		    fallback = loc;
		}
	    } catch (Exception ignored) {
	    }
	}
	return fallback;
    }

    private Object setPosViaJavascript(Page page, String pos) {
	try {
	    return page.evaluate("(posCode) => {"
		    + "  const input = document.querySelector('#placeOfService')"
		    + "    || document.querySelector(\"input[name='placeOfService']\")"
		    + "    || document.querySelector(\"input[name='chargeplaceOfServicever']\");"
		    + "  let container = document.querySelector('#s2id_placeOfService');"
		    + "  if (!container && input) {"
		    + "    container = input.previousElementSibling;"
		    + "    if (!container || !container.classList || !container.classList.contains('select2-container')) {"
		    + "      const labels = Array.from(document.querySelectorAll('label'));"
		    + "      const pl = labels.find(l => (l.textContent || '').trim() === 'POS');"
		    + "      if (pl) container = pl.parentElement && pl.parentElement.querySelector('.select2-container');"
		    + "    }"
		    + "  }"
		    + "  const chosen = container && container.querySelector('.select2-chosen');"
		    + "  if (!input && !chosen) return 'missing-input';"
		    + "  const $ = window.jQuery || window.$;"
		    + "  if (input && $ && $.fn && $.fn.select2) {"
		    + "    try {"
		    + "      $(input).select2('enable', true);"
		    + "      const data = { id: posCode, text: posCode, code: posCode };"
		    + "      $(input).select2('data', data);"
		    + "      $(input).val(posCode).trigger('change');"
		    + "      if (chosen) chosen.textContent = posCode;"
		    + "      return 'ok-select2';"
		    + "    } catch (e) { return 'select2-error:' + e.message; }"
		    + "  }"
		    + "  if (chosen) chosen.textContent = posCode;"
		    + "  if (input) {"
		    + "    input.removeAttribute('disabled'); input.disabled = false;"
		    + "    input.value = posCode;"
		    + "    input.dispatchEvent(new Event('change', { bubbles: true }));"
		    + "  }"
		    + "  return 'ok-dom';"
		    + "}", pos);
	} catch (Exception e) {
	    return "js-exception:" + e.getMessage();
	}
    }

    private void forceEnablePosContainer(Page page, Locator container) {
	try {
	    page.evaluate("() => {"
		    + "  const inputs = ["
		    + "    document.querySelector('#placeOfService'),"
		    + "    document.querySelector(\"input[name='placeOfService']\"),"
		    + "    document.querySelector(\"input[name='chargeplaceOfServicever']\")"
		    + "  ].filter(Boolean);"
		    + "  inputs.forEach(h => { h.removeAttribute('disabled'); h.disabled = false; });"
		    + "  const containers = [];"
		    + "  const c1 = document.querySelector('#s2id_placeOfService');"
		    + "  if (c1) containers.push(c1);"
		    + "  document.querySelectorAll('label').forEach(l => {"
		    + "    if ((l.textContent || '').trim() === 'POS') {"
		    + "      const c = l.parentElement && l.parentElement.querySelector('.select2-container');"
		    + "      if (c) containers.push(c);"
		    + "    }"
		    + "  });"
		    + "  containers.forEach(c => {"
		    + "    c.classList.remove('select2-container-disabled');"
		    + "    c.removeAttribute('disabled');"
		    + "    const f = c.querySelector('.select2-focusser');"
		    + "    if (f) { f.removeAttribute('disabled'); f.disabled = false; }"
		    + "  });"
		    + "  const $ = window.jQuery || window.$;"
		    + "  if ($ && $.fn && $.fn.select2) {"
		    + "    inputs.forEach(h => { try { $(h).select2('enable', true); } catch (e) {} });"
		    + "  }"
		    + "}");
	} catch (Exception ignored) {
	}
    }

    private static String readFormPosChosen(Locator container) {
	try {
	    if (container == null || container.count() == 0) {
		return "";
	    }
	    String t = container.locator(".select2-chosen").first().innerText().trim();
	    if (t.isEmpty() || "\u00a0".equals(t)) {
		return "";
	    }
	    return t;
	} catch (Exception e) {
	    return "";
	}
    }

    private boolean trySelect2SetPos(Page page, Locator container, String pos) {
	try {
	    if (container == null || container.count() == 0) {
		return false;
	    }
	    Locator choice = container.locator("a.select2-choice").first();
	    if (choice.count() == 0) {
		return false;
	    }
	    dismissSelect2(page);
	    choice.click(new Locator.ClickOptions().setForce(true));
	    Thread.sleep(200);
	    Locator search = page.locator(
		    ".select2-drop-active .select2-search input.select2-input, "
			    + ".select2-drop:not(.select2-display-none) .select2-search input.select2-input")
		    .first();
	    try {
		search.waitFor(new Locator.WaitForOptions()
			.setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
			.setTimeout(3000));
	    } catch (Exception e) {
		page.keyboard().press("Escape");
		return false;
	    }
	    search.fill("");
	    search.fill(pos);
	    Thread.sleep(500);
	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, "
			    + ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");
	    for (int i = 0; i < results.count(); i++) {
		String text = "";
		try {
		    text = results.nth(i).innerText().trim();
		} catch (Exception ignored) {
		}
		if (posMatches(text, pos)) {
		    results.nth(i).click(new Locator.ClickOptions().setForce(true));
		    Thread.sleep(200);
		    return true;
		}
	    }
	    if (results.count() > 0) {
		results.first().click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(200);
		return true;
	    }
	    page.keyboard().press("Escape");
	} catch (Exception e) {
	    logger.warn("trySelect2SetPos failed: {}", e.getMessage());
	}
	return false;
    }

    private static boolean posMatches(String pageVal, String pos) {
	if (pos == null || pos.isBlank()) {
	    return true;
	}
	if (pageVal == null || pageVal.isBlank()) {
	    return false;
	}
	String p = pageVal.trim();
	String want = pos.trim();
	if (p.equalsIgnoreCase(want)) {
	    return true;
	}
	if (p.regionMatches(true, 0, want, 0, want.length())) {
	    if (p.length() == want.length()) {
		return true;
	    }
	    char next = p.charAt(want.length());
	    return next == ' ' || next == '-' || next == '\t';
	}
	return false;
    }

    /**
     * Opens a select2 in the CPT row whose container id/class hints at {@code hint},
     * types {@code value}, selects first result.
     */
    private boolean setSelect2InRow(Page page, Locator row, String value, String hint, String desc) {
	if (value == null || value.isBlank()) {
	    return false;
	}
	try {
	    Locator containers = row.locator("div.select2-container");
	    Locator choice = null;
	    String hintLower = hint.toLowerCase(Locale.ROOT);
	    for (int i = 0; i < containers.count(); i++) {
		Locator c = containers.nth(i);
		String id = String.valueOf(c.getAttribute("id"));
		String cls = String.valueOf(c.getAttribute("class"));
		String blob = (id + " " + cls).toLowerCase(Locale.ROOT);
		// Never target POS / placeOfService — often auto-populated & disabled
		if (blob.contains("placeofservice") || blob.contains("place_of_service")
			|| blob.contains("placeofservicever")
			|| (cls != null && cls.contains("select2-container-disabled"))) {
		    continue;
		}
		if (blob.contains(hintLower)) {
		    Locator a = c.locator("a.select2-choice").first();
		    if (a.count() > 0 && a.isVisible()) {
			choice = a;
			break;
		    }
		}
	    }
	    if (choice == null) {
		return false;
	    }
	    PlayTestActionLog.update(desc, "select2 -> '" + value + "'");
	    choice.click();
	    Thread.sleep(200);
	    Locator search = page.locator(
		    ".select2-drop-active .select2-search input.select2-input, .select2-drop:not(.select2-display-none) .select2-search input.select2-input")
		    .first();
	    search.waitFor(new Locator.WaitForOptions()
		    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(5000));
	    search.fill(value);
	    Thread.sleep(600);
	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, .select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");
	    if (results.count() > 0) {
		results.first().click();
		Thread.sleep(200);
		return true;
	    }
	    page.keyboard().press("Escape");
	} catch (Exception e) {
	    logger.warn("Unable to set select2 {}: {}", desc, e.getMessage());
	}
	return false;
    }

    private Locator findRowField(Locator row, String keyFragment) {
	String frag = keyFragment.toLowerCase(Locale.ROOT);
	String[] selectors = {
		"input[ng-model*='" + frag + "']",
		"input[name*='" + frag + "']",
		"input[id*='" + frag + "']",
		"textarea[ng-model*='" + frag + "']",
		"textarea[name*='" + frag + "']",
		"textarea[id*='" + frag + "']"
	};
	for (String sel : selectors) {
	    try {
		Locator loc = row.locator(sel);
		for (int i = 0; i < loc.count(); i++) {
		    Locator candidate = loc.nth(i);
		    if (candidate.isVisible()) {
			return candidate;
		    }
		}
	    } catch (Exception ignored) {
	    }
	}
	Locator all = row.locator("input, textarea");
	for (int i = 0; i < all.count(); i++) {
	    Locator candidate = all.nth(i);
	    try {
		if (!candidate.isVisible()) {
		    continue;
		}
		String blob = (safeAttr(candidate, "ng-model") + " " + safeAttr(candidate, "name") + " "
			+ safeAttr(candidate, "id") + " " + safeAttr(candidate, "class")).toLowerCase(Locale.ROOT);
		if (blob.contains("select2")) {
		    continue;
		}
		if (blob.contains(frag)) {
		    return candidate;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    private static String safeAttr(Locator loc, String name) {
	try {
	    String v = loc.getAttribute(name);
	    return v == null ? "" : v;
	} catch (Exception e) {
	    return "";
	}
    }

    private static Locator firstPresent(Locator... candidates) {
	if (candidates == null) {
	    return null;
	}
	for (Locator c : candidates) {
	    if (c != null) {
		return c;
	    }
	}
	return null;
    }

    private static String firstNonBlank(String... values) {
	if (values == null) {
	    return null;
	}
	for (String v : values) {
	    if (v != null && !v.isBlank()) {
		return v;
	    }
	}
	return null;
    }

    private List<Locator> visibleTextInputs(Locator row) {
	List<Locator> out = new ArrayList<>();
	Locator inputs = row.locator("td input[type='text'], td input:not([type]), td input.form-control");
	int count = inputs.count();
	for (int i = 0; i < count; i++) {
	    Locator input = inputs.nth(i);
	    try {
		if (!input.isVisible()) {
		    continue;
		}
		String cls = String.valueOf(input.getAttribute("class"));
		if (cls != null && cls.contains("select2-input")) {
		    continue;
		}
		out.add(input);
	    } catch (Exception ignored) {
	    }
	}
	return out;
    }

    private void setInputIfPresent(Locator input, String value, String desc) {
	if (input == null || value == null) {
	    return;
	}
	try {
	    String current = "";
	    try {
		current = input.inputValue();
	    } catch (Exception ignored) {
	    }
	    if (value.equalsIgnoreCase(current == null ? "" : current.trim())) {
		PlayTestActionLog.skip(desc, current);
		return;
	    }
	    PlayTestActionLog.update(desc, "'" + current + "' -> '" + value + "'");
	    input.click();
	    input.fill("");
	    input.fill(value);
	    input.press("Tab");
	    Thread.sleep(300);
	} catch (Exception e) {
	    logger.warn("Unable to set {}: {}", desc, e.getMessage());
	    PlayTestActionLog.skip(desc, e.getMessage());
	}
    }

    private Locator findCptRow(Page page, String code) {
	String want = normalizeCptCode(code);
	Locator rows = page.locator(CPT_ROWS);
	int count = rows.count();
	for (int i = 0; i < count; i++) {
	    Locator row = rows.nth(i);
	    if (want.equals(normalizeCptCode(readCptCode(row)))) {
		return row;
	    }
	}
	return null;
    }

    private Set<String> collectCptUiCodes(Page page) {
	Set<String> uiValues = new HashSet<>();
	Locator rows = page.locator(CPT_ROWS);
	int rowCount = rows.count();
	for (int i = 0; i < rowCount; i++) {
	    try {
		String value = readCptCode(rows.nth(i));
		if (!value.isBlank()) {
		    uiValues.add(normalizeCptCode(value));
		}
	    } catch (Exception ignored) {
	    }
	}
	return uiValues;
    }

    /**
     * Removes every removable CPT charge row (filled or blank). The trailing {@code *}
     * add-row has no removeCharge button and is left alone so new CPTs can be typed in.
     */
    private void deleteAllCptRows(Page page) {
	for (int pass = 0; pass < 15; pass++) {
	    Locator rows = page.locator(CPT_ROWS);
	    int rowCount = rows.count();
	    logger.info("CPT delete-all pass {}: {} row(s), UI codes={}", pass + 1, rowCount,
		    collectCptUiCodes(page));

	    boolean deletedAny = false;
	    // Delete from end so indices stay stable; one click per outer pass restart
	    for (int i = rowCount - 1; i >= 0; i--) {
		try {
		    rows = page.locator(CPT_ROWS);
		    if (i >= rows.count()) {
			continue;
		    }
		    Locator row = rows.nth(i);
		    Locator removeBtn = findCptRemoveButton(row);
		    if (removeBtn == null) {
			continue; // * add-row
		    }
		    String value = readCptCode(row);
		    String label = value.isBlank() ? "(blank)" : value;
		    PlayTestActionLog.delete("CPT row", label + " (clear-all)");
		    logger.info("Deleting CPT row '{}' (clear-all)", label);
		    dismissSelect2(page);
		    removeBtn.scrollIntoViewIfNeeded();
		    removeBtn.click(new Locator.ClickOptions().setForce(true));
		    Thread.sleep(500);
		    if (findDisableChargeForm(page) != null) {
			if (!confirmDisableChargeDialog(page, "ABI only")) {
			    logger.warn("Disable charge dialog not confirmed for CPT {}", label);
			    PlayTestActionLog.skip("CPT delete " + label, "Disable charge not confirmed");
			}
		    }
		    page.waitForTimeout(400);
		    deletedAny = true;
		    break; // re-scan from end after DOM change
		} catch (Exception e) {
		    logger.warn("CPT delete-all failed: {}", e.getMessage());
		}
	    }
	    if (!deletedAny) {
		logger.info("CPT delete-all: no more removable rows");
		break;
	    }
	}
	logger.info("CPT delete-all finished; remaining UI codes={}", collectCptUiCodes(page));
    }

    /**
     * Removes blank (no procedure code) charge rows that still have a remove button —
     * leftover empty rows that are not the trailing {@code *} add-row.
     */
    private void deleteBlankRemovableCptRows(Page page) {
	for (int pass = 0; pass < 10; pass++) {
	    Locator rows = page.locator(CPT_ROWS);
	    boolean deletedAny = false;
	    for (int i = rows.count() - 1; i >= 0; i--) {
		try {
		    rows = page.locator(CPT_ROWS);
		    if (i >= rows.count()) {
			continue;
		    }
		    Locator row = rows.nth(i);
		    if (!readCptCode(row).isBlank()) {
			continue;
		    }
		    Locator removeBtn = findCptRemoveButton(row);
		    if (removeBtn == null) {
			continue;
		    }
		    PlayTestActionLog.delete("CPT row", "(blank leftover)");
		    logger.info("Deleting blank leftover CPT row");
		    dismissSelect2(page);
		    removeBtn.scrollIntoViewIfNeeded();
		    removeBtn.click(new Locator.ClickOptions().setForce(true));
		    Thread.sleep(400);
		    if (findDisableChargeForm(page) != null) {
			confirmDisableChargeDialog(page, "ABI only");
		    }
		    page.waitForTimeout(300);
		    deletedAny = true;
		    break;
		} catch (Exception e) {
		    logger.warn("Blank CPT delete failed: {}", e.getMessage());
		}
	    }
	    if (!deletedAny) {
		return;
	    }
	}
    }

    /**
     * Removes every CPT charge row whose procedure code is not in {@code expectedNormalized}.
     * Clicks only {@code removeCharge} (glyphicon-remove) — never other row buttons.
     * Retries until UI codes are a subset of JSON or max passes exhausted.
     * Blank rows are ignored here; use {@link #deleteBlankRemovableCptRows} / {@link #deleteAllCptRows}.
     */
    private void deleteUnwantedCptRows(Page page, Set<String> expectedNormalized) {
	for (int pass = 0; pass < 5; pass++) {
	    Locator rows = page.locator(CPT_ROWS);
	    int rowCount = rows.count();
	    Set<String> before = collectCptUiCodes(page);
	    logger.info("CPT delete pass {}: UI={} JSON keep={}", pass + 1, before, expectedNormalized);

	    // Already clean?
	    if (before.isEmpty() || expectedNormalized.containsAll(before)) {
		logger.info("CPT delete: UI codes already within JSON set");
		return;
	    }

	    boolean deletedAny = false;
	    for (int i = rowCount - 1; i >= 0; i--) {
		try {
		    rows = page.locator(CPT_ROWS);
		    if (i >= rows.count()) {
			continue;
		    }
		    Locator row = rows.nth(i);
		    String value = readCptCode(row);
		    if (value.isBlank()) {
			continue;
		    }
		    String norm = normalizeCptCode(value);
		    if (expectedNormalized.contains(norm)) {
			PlayTestActionLog.skip("CPT row", "keeping '" + value + "' (in JSON)");
			continue;
		    }

		    PlayTestActionLog.delete("CPT row", value + " (not in JSON)");
		    logger.info("Deleting CPT '{}' — not present in JSON", value);
		    dismissSelect2(page);

		    Locator removeBtn = findCptRemoveButton(row);
		    if (removeBtn == null) {
			PlayTestActionLog.skip("CPT delete " + value, "removeCharge button not found");
			logger.warn("No removeCharge button for CPT {} (last empty row has none)", value);
			continue;
		    }
		    removeBtn.scrollIntoViewIfNeeded();
		    removeBtn.click(new Locator.ClickOptions().setForce(true));
		    Thread.sleep(500);

		    // Disable-charge modal: set native select reason so OK enables, then click OK
		    if (!confirmDisableChargeDialog(page, "ABI only")) {
			logger.warn("Disable charge dialog not confirmed for CPT {}", value);
			PlayTestActionLog.skip("CPT delete " + value, "Disable charge not confirmed");
		    }
		    page.waitForTimeout(500);

		    // Confirm this code is gone (or count dropped)
		    Set<String> after = collectCptUiCodes(page);
		    if (!after.contains(norm)) {
			logger.info("CPT '{}' deleted successfully", value);
			deletedAny = true;
		    } else {
			logger.warn("CPT '{}' still present after delete attempt", value);
		    }
		} catch (Exception e) {
		    logger.warn("CPT delete failed: {}", e.getMessage());
		}
	    }
	    if (!deletedAny) {
		logger.warn("CPT delete pass {}: no rows removed; remaining UI={}", pass + 1,
			collectCptUiCodes(page));
		break;
	    }
	}
	Set<String> leftover = collectCptUiCodes(page);
	leftover.removeAll(expectedNormalized);
	if (!leftover.isEmpty()) {
	    logger.warn("CPT codes still on UI but not in JSON after delete: {}", leftover);
	}
    }

    /** The red X that calls removeCharge(charge) — not department/DOS/location toggles. */
    private Locator findCptRemoveButton(Locator row) {
	String[] selectors = {
		"button[ng-click*='removeCharge']",
		"button.btn-danger.btn-xs:has(i.glyphicon-remove)",
		"td > button.btn-danger.btn-xs",
		"button.btn-danger.btn-xs"
	};
	for (String sel : selectors) {
	    try {
		Locator btn = row.locator(sel).first();
		if (btn.count() > 0 && btn.isVisible()) {
		    return btn;
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    private String readCptCode(Locator row) {
	try {
	    // Prefer procedure Select2 only — avoid modifiers / location / POS chosen text
	    String[] procedureSelectors = {
		    "[ng-controller*='ProcedureController'] .select2-chosen",
		    "[type='procedure'] .select2-chosen",
		    "td:nth-child(2) .select2-chosen",
		    "td:nth-child(2) a.select2-choice"
	    };
	    for (String sel : procedureSelectors) {
		Locator chosen = row.locator(sel).first();
		if (chosen.count() > 0) {
		    String t = chosen.innerText().trim();
		    if (!t.isEmpty() && !"\u00a0".equals(t)) {
			return firstToken(t);
		    }
		}
	    }
	    String value = row.locator("td:nth-child(2)").innerText().trim();
	    if (value.contains("\n")) {
		value = value.split("\\R", 2)[0].trim();
	    }
	    return firstToken(value);
	} catch (Exception e) {
	    return "";
	}
    }

    /** Uppercase CPT code token for comparisons. */
    private static String normalizeCptCode(String raw) {
	if (raw == null) {
	    return "";
	}
	return firstToken(raw).toUpperCase(Locale.ROOT);
    }

    /**
     * Maps JSON diagnoses (ICD codes or already-numeric pointers) to CPT Diagnoses
     * column values like {@code 1} or {@code 1,2}.
     */
    static String toDiagnosisPointers(String diagnoses, List<String> icdOrder) {
	if (diagnoses == null || diagnoses.isBlank()) {
	    return null;
	}
	String trimmed = diagnoses.trim();
	if (trimmed.matches("\\d+(\\s*,\\s*\\d+)*")) {
	    return trimmed.replaceAll("\\s+", "");
	}
	if (icdOrder == null || icdOrder.isEmpty()) {
	    return null;
	}
	String[] parts = trimmed.split("[,;]+");
	List<String> pointers = new ArrayList<>();
	for (String part : parts) {
	    String code = part.trim().toUpperCase(Locale.ROOT);
	    if (code.isEmpty()) {
		continue;
	    }
	    for (int i = 0; i < icdOrder.size(); i++) {
		if (code.equalsIgnoreCase(icdOrder.get(i))) {
		    pointers.add(String.valueOf(i + 1));
		    break;
		}
	    }
	}
	return pointers.isEmpty() ? null : String.join(",", pointers);
    }

    private static String unitsToString(Object units) {
	if (units == null) {
	    return null;
	}
	String s = String.valueOf(units).trim();
	if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
	    return null;
	}
	if (s.endsWith(".0")) {
	    s = s.substring(0, s.length() - 2);
	}
	return s;
    }

    private static String str(Map<String, Object> map, String key) {
	if (map == null) {
	    return null;
	}
	Object v = map.get(key);
	if (v == null) {
	    return null;
	}
	String s = String.valueOf(v).trim();
	return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

	//expectedValues contains json ICDs
    void validateICD(Set<String> expectedValues, Page page) {
	List<String> ordered = new ArrayList<>();
	if (expectedValues != null) {
	    ordered.addAll(expectedValues);
	}
	validateICD(ordered, page);
    }

    /**
     * Sync ICD rows to JSON order: delete extras, add missing (with retry / dotted variants).
     */
    void validateICD(List<String> expectedValues, Page page) {
	PlayTestActionLog.step("validateICD — expected: " + expectedValues);

	try {
	    Thread.sleep(300);

	    Locator rows = page.locator(ICD_ROWS);
	    rows.first().waitFor();

	    List<String> ordered = new ArrayList<>();
	    Set<String> expectedKeys = new HashSet<>();
	    if (expectedValues != null) {
		for (String e : expectedValues) {
		    if (e == null || e.isBlank()) {
			continue;
		    }
		    String trimmed = e.trim();
		    String key = normalizeIcdKey(trimmed);
		    if (key.isEmpty() || expectedKeys.contains(key)) {
			continue;
		    }
		    expectedKeys.add(key);
		    ordered.add(trimmed);
		}
	    }

	    Set<String> uiValues = collectIcdUiKeys(page);
	    logger.info("ICD UI values before sync: {}", uiValues);



	    uiValues = collectIcdUiKeys(page);
	    logger.info("ICD UI values after delete: {}", uiValues);

		//Delete all ICDs
		deleteAllIcdRows(page);

		//Add all ICDs from JSON
	    addAllIcdCodes(page, ordered);

	} catch (Exception e) {
	    logger.warn("validateICD failed: {}", e.getMessage(), e);
	    PlayTestActionLog.skip("validateICD", e.getMessage());
	}
    }

	/**
	 * Adds every ICD from the JSON list to the UI.
	 * After completion, retries only the ICDs still missing from the page.
	 */
	private void addAllIcdCodes(Page page, List<String> ordered)
			throws InterruptedException {

		if (ordered == null || ordered.isEmpty()) {
			logger.info("No ICDs present in JSON.");
			return;
		}

		// ---------- First Pass ----------
		for (String expected : ordered) {

			if (expected == null || expected.isBlank()) {
				continue;
			}

			PlayTestActionLog.add("ICD", expected);

			boolean added = false;

			for (String query : icdQueryVariants(expected)) {

				dismissSelect2(page);

				Locator emptyRow = findEmptyIcdRow(page);

				if (emptyRow == null) {
					logger.warn("No empty ICD row available for {}", expected);
					break;
				}

				added = select2TypeAndChooseIcd(page, emptyRow, query, "ICD " + expected);

				Thread.sleep(400);

				if (icdPresentOnPage(page, normalizeIcdKey(expected))) {

					logger.info("Added ICD {}", expected);

					PlayTestActionLog.update(
							"ICD verify",
							"Added '" + expected + "'");

					added = true;
					break;
				}

				logger.warn("ICD {} not committed using '{}'", expected, query);
			}

			if (!added) {
				logger.warn("Unable to add ICD {}", expected);

				PlayTestActionLog.skip(
						"ICD " + expected,
						"Unable to add");
			}
		}

		// ---------- Verification ----------
		Set<String> uiCodes = collectIcdUiKeys(page);

		List<String> missing = new ArrayList<>();

		for (String expected : ordered) {

			if (!uiCodes.contains(normalizeIcdKey(expected))) {
				missing.add(expected);
			}
		}

		if (missing.isEmpty()) {

			logger.info("All ICDs successfully added.");

			return;
		}

		logger.warn("Missing ICDs after first pass: {}", missing);

		// ---------- Retry Missing ICDs ----------
		for (String expected : missing) {

			boolean added = false;

			for (String query : icdQueryVariants(expected)) {

				dismissSelect2(page);

				Locator emptyRow = findEmptyIcdRow(page);

				if (emptyRow == null) {
					break;
				}

				added = select2TypeAndChooseIcd(page, emptyRow, query, "Retry ICD " + expected);

				Thread.sleep(400);

				if (icdPresentOnPage(page, normalizeIcdKey(expected))) {

					logger.info("Retry succeeded for ICD {}", expected);

					added = true;
					break;
				}
			}

			if (!added) {
				logger.error("Retry failed for ICD {}", expected);
			}
		}

		// ---------- Final Verification ----------
		uiCodes = collectIcdUiKeys(page);

		List<String> stillMissing = new ArrayList<>();

		for (String expected : ordered) {

			if (!uiCodes.contains(normalizeIcdKey(expected))) {
				stillMissing.add(expected);
			}
		}

		if (stillMissing.isEmpty()) {
			logger.info("Final ICD verification successful.");
		} else {
			logger.error("Following ICDs are still missing after retry: {}", stillMissing);
		}
	}

    /** Prefer first blank ICD row (asterisk / empty chosen); else last row. */
    private Locator findEmptyIcdRow(Page page) {
	Locator rows = page.locator(ICD_ROWS);
	for (int i = 0; i < rows.count(); i++) {
	    Locator row = rows.nth(i);
	    if (readIcdCode(row).isBlank()) {
		return row;
	    }
	}
	return rows.count() > 0 ? rows.last() : null;
    }

    private boolean icdPresentOnPage(Page page, String key) {
	return collectIcdUiKeys(page).contains(key);
    }

    /** Search variants: as-given, dotted, undotted (E11.65 ↔ E1165). */
    private static List<String> icdQueryVariants(String code) {
	List<String> out = new ArrayList<>();
	if (code == null || code.isBlank()) {
	    return out;
	}
	String raw = code.trim();
	out.add(raw);
	String noDot = raw.replace(".", "");
	if (!noDot.equalsIgnoreCase(raw)) {
	    out.add(noDot);
	}
	// Insert dot after 3rd char when missing (E1165 → E11.65) for common ICD-10 shape
	if (!raw.contains(".") && raw.length() >= 4) {
	    String dotted = raw.substring(0, 3) + "." + raw.substring(3);
	    if (!out.contains(dotted)) {
		out.add(dotted);
	    }
	}
	return out;
    }

    /**
     * ICD Select2: type query and click a result whose code key matches (never click first blindly).
     */
    private boolean select2TypeAndChooseIcd(Page page, Locator row, String query, String desc) {
	try {
	    dismissSelect2(page);
	    Thread.sleep(150);

	    Locator opener = firstVisibleOpener(row);
	    if (opener == null) {
		logger.warn("{}: no select2 opener on row", desc);
		return false;
	    }
	    opener.scrollIntoViewIfNeeded();
	    opener.click(new Locator.ClickOptions().setForce(true));
	    Thread.sleep(200);

	    Locator search = visibleSelect2Search(page);
	    if (search == null) {
		dismissSelect2(page);
		Thread.sleep(100);
		opener.click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(250);
		search = visibleSelect2Search(page);
	    }
	    if (search == null) {
		logger.warn("{}: select2 search input not visible", desc);
		return false;
	    }

	    search.click(new Locator.ClickOptions().setForce(true));
	    search.fill("");
	    search.fill(query);
	    Thread.sleep(800);

	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, "
			    + ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");

	    for (int i = 0; i < 10 && results.count() == 0; i++) {
		if (page.locator(
			".select2-drop-active .select2-results li.select2-no-results, "
				+ ".select2-drop:not(.select2-display-none) .select2-results li.select2-no-results")
			.count() > 0) {
		    logger.warn("{}: select2 no results for '{}'", desc, query);
		    dismissSelect2(page);
		    return false;
		}
		Thread.sleep(250);
	    }
	    if (results.count() == 0) {
		logger.warn("{}: timed out waiting for select2 results for '{}'", desc, query);
		dismissSelect2(page);
		return false;
	    }

	    String wantKey = normalizeIcdKey(query);
	    Locator match = null;
	    for (int i = 0; i < results.count(); i++) {
		String text = results.nth(i).innerText().trim();
		String resultKey = normalizeIcdKey(firstToken(text));
		if (resultKey.equals(wantKey) || resultKey.startsWith(wantKey) || wantKey.startsWith(resultKey)) {
		    match = results.nth(i);
		    break;
		}
		String upper = text.toUpperCase(Locale.ROOT);
		if (upper.startsWith(query.toUpperCase(Locale.ROOT))
			|| upper.contains(query.toUpperCase(Locale.ROOT))) {
		    match = results.nth(i);
		    break;
		}
	    }
	    if (match == null) {
		logger.warn("{}: no result key-matching '{}' — not clicking unrelated first row", desc, query);
		dismissSelect2(page);
		return false;
	    }
	    match.click(new Locator.ClickOptions().setForce(true));
	    Thread.sleep(300);
	    dismissSelect2(page);
	    return true;
	} catch (Exception e) {
	    logger.warn("{} ICD select2 failed: {}", desc, e.getMessage());
	    try {
		dismissSelect2(page);
	    } catch (Exception ignored) {
	    }
	    return false;
	}
    }

    private Set<String> collectIcdUiKeys(Page page) {
	Set<String> uiValues = new HashSet<>();
	Locator rows = page.locator(ICD_ROWS);
	for (int i = 0; i < rows.count(); i++) {
	    try {
		String value = readIcdCode(rows.nth(i));
		if (!value.isBlank()) {
		    uiValues.add(normalizeIcdKey(value));
		}
	    } catch (Exception ignored) {
	    }
	}
	return uiValues;
    }

    /*
	 * Deletes ALL existing ICD rows from the UI.
	 * Leaves only the blank placeholder row (if present).
	 */
	private void deleteAllIcdRows(Page page) {
		for (int pass = 0; pass < 5; pass++) {

			Locator rows = page.locator(ICD_ROWS);
			int rowCount = rows.count();
			boolean deletedAny = false;

			for (int i = rowCount - 1; i >= 0; i--) {
				try {

					rows = page.locator(ICD_ROWS);
					if (i >= rows.count()) {
						continue;
					}

					Locator row = rows.nth(i);

					String value = readIcdCode(row);

					// Skip the empty placeholder row
					if (value.isBlank()) {
						continue;
					}

					PlayTestActionLog.delete("ICD row", value);
					logger.info("Deleting ICD '{}'", value);

					dismissSelect2(page);

					Locator del = row.locator(
									"button.btn-danger, " +
											"button:has(i.glyphicon-remove), " +
											"button[ng-click*='remove'], " +
											"button")
							.first();

					if (del.count() == 0) {
						logger.warn("Delete button not found for ICD {}", value);
						continue;
					}

					del.scrollIntoViewIfNeeded();
					del.click(new Locator.ClickOptions().setForce(true));

					Thread.sleep(500);

					if (findDisableChargeForm(page) != null) {
						confirmDisableChargeDialog(page, "ABI only");
					}

					Thread.sleep(500);

					deletedAny = true;

				} catch (Exception e) {
					logger.warn("Failed deleting ICD row: {}", e.getMessage());
				}
			}

			if (!deletedAny) {
				logger.info("No more ICD rows left to delete.");
				break;
			}
		}

		logger.info("Finished deleting all ICD rows.");
	}

    /** Uppercase ICD without dots/spaces for stable UI↔JSON matching. */
    private static String normalizeIcdKey(String raw) {
	if (raw == null) {
	    return "";
	}
	return firstToken(raw).toUpperCase(Locale.ROOT).replace(".", "").replace(" ", "");
    }

    /** Reads ICD code text from a diagnosis row (relative to the row, not document root). */
    private String readIcdCode(Locator row) {
	try {
	    Locator chosen = row.locator(".select2-chosen").first();
	    if (chosen.count() > 0) {
		String t = chosen.innerText().trim();
		if (!t.isEmpty() && !"\u00a0".equals(t)) {
		    return firstToken(t);
		}
	    }
	    Locator choice = row.locator("a.select2-choice").first();
	    if (choice.count() > 0) {
		String t = choice.innerText().trim();
		if (!t.isEmpty() && !"\u00a0".equals(t)) {
		    return firstToken(t);
		}
	    }
	    Locator link = row.locator("xpath=.//a").first();
	    if (link.count() > 0) {
		String t = link.innerText().trim();
		if (!t.isEmpty()) {
		    return firstToken(t);
		}
	    }
	} catch (Exception ignored) {
	}
	return "";
    }

    private static String firstToken(String text) {
	String t = text.trim();
	if (t.contains("\n")) {
	    t = t.split("\\R", 2)[0].trim();
	}
	// "S82.152A - Description" or "S82.152A Description"
	String[] parts = t.split("[\\s\\-•]+", 2);
	return parts[0].trim();
    }

    /**
     * Opens the Select2 on a row, types {@code query}, waits for results, clicks a matching
     * result (or first result), dismisses leftover overlays.
     */
    private boolean select2TypeAndChoose(Page page, Locator row, String query, String desc) {
	try {
	    dismissSelect2(page);
	    Thread.sleep(150);

	    Locator opener = firstVisibleOpener(row);
	    if (opener == null) {
		logger.warn("{}: no select2 opener on row", desc);
		return false;
	    }
	    opener.scrollIntoViewIfNeeded();
	    opener.click(new Locator.ClickOptions().setForce(true));
	    Thread.sleep(200);

	    Locator search = visibleSelect2Search(page);
	    if (search == null) {
		// Retry open once
		dismissSelect2(page);
		Thread.sleep(100);
		opener.click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(250);
		search = visibleSelect2Search(page);
	    }
	    if (search == null) {
		logger.warn("{}: select2 search input not visible", desc);
		return false;
	    }

	    search.click(new Locator.ClickOptions().setForce(true));
	    search.fill("");
	    search.fill(query);
	    Thread.sleep(700);

	    Locator results = page.locator(
		    ".select2-drop-active .select2-results li.select2-result-selectable, "
			    + ".select2-drop:not(.select2-display-none) .select2-results li.select2-result-selectable");

	    // Wait up to ~2s for results
	    for (int i = 0; i < 8 && results.count() == 0; i++) {
		if (page.locator(
			".select2-drop-active .select2-results li.select2-no-results, "
				+ ".select2-drop:not(.select2-display-none) .select2-results li.select2-no-results")
			.count() > 0) {
		    logger.warn("{}: select2 no results for '{}'", desc, query);
		    dismissSelect2(page);
		    return false;
		}
		Thread.sleep(250);
	    }
	    if (results.count() == 0) {
		logger.warn("{}: timed out waiting for select2 results for '{}'", desc, query);
		dismissSelect2(page);
		return false;
	    }

	    Locator match = null;
	    String want = query.toUpperCase(Locale.ROOT);
	    for (int i = 0; i < results.count(); i++) {
		String text = results.nth(i).innerText().trim().toUpperCase(Locale.ROOT);
		if (text.startsWith(want) || text.contains(want)) {
		    match = results.nth(i);
		    break;
		}
	    }
	    if (match == null) {
		match = results.first();
	    }
	    match.click(new Locator.ClickOptions().setForce(true));
	    Thread.sleep(250);
	    dismissSelect2(page);
	    // Changing an existing charge can open "Disable charge" — confirm only if already visible
	    if (desc != null && desc.startsWith("CPT ") && findDisableChargeForm(page) != null) {
		confirmDisableChargeDialog(page, "ABI only");
	    }
	    return true;
	} catch (Exception e) {
	    logger.warn("{} select2 failed: {}", desc, e.getMessage());
	    try {
		dismissSelect2(page);
	    } catch (Exception ignored) {
	    }
	    return false;
	}
    }

    /**
     * Confirms Zotec "Disable Charge" modal.
     * OK stays {@code disabled} until {@code select[name=disabledReasonId]} has a value
     * (Angular {@code form.$invalid}). Set the native select first, wait for OK enabled, then click.
     */
    private boolean confirmDisableChargeDialog(Page page, String reason) {
	try {
	    Locator form = waitForDisableChargeForm(page, 3000);
	    if (form == null) {
		logger.info("No Disable charge dialog visible");
		return false;
	    }

	    String[] reasonPrefs = reasonPrefs(reason);
	    PlayTestActionLog.update("Disable charge",
		    "dialog open — setting reason via select[name=disabledReasonId]");

	    if (!setDisableChargeReasonOnSelect(page, form, reasonPrefs)) {
		logger.warn("Could not set Disable Charge reason on native select");
		PlayTestActionLog.skip("Disable charge", "reason select failed");
		return false;
	    }

	    Locator ok = form.locator(".modal-footer button.btn-primary, button.btn-primary:has-text('OK')")
		    .first();
	    if (ok.count() == 0) {
		ok = page.locator("form[name='form'] .modal-footer button.btn-primary,"
			+ " .modal-footer button.btn-primary:has-text('OK')").first();
	    }
	    if (ok.count() == 0) {
		logger.warn("Disable charge OK button not in footer");
		logModalButtons(form);
		return false;
	    }

	    // Wait until Angular clears form.$invalid and removes disabled
	    boolean enabled = waitUntilOkEnabled(ok, 4000);
	    if (!enabled) {
		logger.warn("OK still disabled after reason set — force-enabling");
		forceEnableDisableChargeOk(page, form);
		Thread.sleep(200);
	    }

	    ok.scrollIntoViewIfNeeded();
	    ok.click(new Locator.ClickOptions().setForce(true));
	    Thread.sleep(500);

	    // Confirm dialog closed
	    Locator stillOpen = page.locator("form[name='form'] .modal-title:has-text('Disable Charge'),"
		    + " .modal-title:has-text('Disable Charge')");
	    try {
		if (stillOpen.count() > 0 && stillOpen.first().isVisible()) {
		    // retry force click after JS enable
		    forceEnableDisableChargeOk(page, form);
		    form.locator(".modal-footer button.btn-primary").first()
			    .click(new Locator.ClickOptions().setForce(true));
		    Thread.sleep(500);
		}
	    } catch (Exception ignored) {
	    }

	    PlayTestActionLog.update("Disable charge", "OK clicked");
	    return !isDisableChargeFormVisible(page);
	} catch (Exception e) {
	    logger.warn("confirmDisableChargeDialog failed: {}", e.getMessage());
	    return false;
	}
    }

    private static String[] reasonPrefs(String preferred) {
	if (preferred != null && !preferred.isBlank()) {
	    return new String[] { preferred.trim(), "ABI only", "0-Code Assist Deletion", "Incorrect CPT Code",
		    "Duplicate" };
	}
	return new String[] { "ABI only", "0-Code Assist Deletion", "Incorrect CPT Code", "Duplicate" };
    }

    private Locator waitForDisableChargeForm(Page page, int timeoutMs) {
	long deadline = System.currentTimeMillis() + timeoutMs;
	while (System.currentTimeMillis() < deadline) {
	    Locator form = findDisableChargeForm(page);
	    if (form != null) {
		return form;
	    }
	    try {
		Thread.sleep(150);
	    } catch (InterruptedException e) {
		Thread.currentThread().interrupt();
		return null;
	    }
	}
	return findDisableChargeForm(page);
    }

    private boolean isDisableChargeFormVisible(Page page) {
	try {
	    Locator form = findDisableChargeForm(page);
	    return form != null;
	} catch (Exception e) {
	    return false;
	}
    }

    /**
     * Prefer the Disable Charge {@code form[name=form]} that owns {@code select[name=disabledReasonId]}.
     */
    private Locator findDisableChargeForm(Page page) {
	try {
	    Locator bySelect = page.locator("form:has(select[name='disabledReasonId'])").first();
	    if (bySelect.count() > 0 && bySelect.isVisible()) {
		return bySelect;
	    }
	} catch (Exception ignored) {
	}
	try {
	    Locator byTitle = page.locator(
		    "form:has(.modal-title:has-text('Disable Charge')), "
			    + ".modal:has(.modal-title:has-text('Disable Charge')) form[name='form']")
		    .first();
	    if (byTitle.count() > 0 && byTitle.isVisible()) {
		return byTitle;
	    }
	} catch (Exception ignored) {
	}
	// Fallback: any visible modal containing Disable Charge text
	try {
	    Locator modals = page.locator(".modal.in, .modal.show, .modal.fade.in, div.modal");
	    for (int i = 0; i < modals.count(); i++) {
		Locator m = modals.nth(i);
		if (!m.isVisible()) {
		    continue;
		}
		String text = "";
		try {
		    text = m.innerText();
		} catch (Exception ignored) {
		}
		if (text != null && text.toLowerCase(Locale.ROOT).contains("disable charge")) {
		    Locator form = m.locator("form[name='form'], form").first();
		    if (form.count() > 0) {
			return form;
		    }
		    return m;
		}
	    }
	} catch (Exception ignored) {
	}
	return null;
    }

    /**
     * Sets {@code select[name=disabledReasonId]} by label (and known value ids), syncs Select2 display,
     * and dispatches change so Angular enables OK.
     */
    private boolean setDisableChargeReasonOnSelect(Page page, Locator form, String[] reasonPrefs) {
	Locator select = form.locator("select[name='disabledReasonId']").first();
	if (select.count() == 0) {
	    select = page.locator("select[name='disabledReasonId']").first();
	}
	if (select.count() == 0) {
	    logger.warn("select[name=disabledReasonId] not found");
	    return false;
	}

	for (String label : reasonPrefs) {
	    if (label == null || label.isBlank()) {
		continue;
	    }
	    try {
		// 1) Native selectOption by label
		select.selectOption(new SelectOption().setLabel(label));
		Thread.sleep(150);
		String val = select.inputValue();
		if (val != null && !val.isBlank()) {
		    syncDisableReasonToAngular(page, val, label);
		    logger.info("Disable Charge reason set via selectOption label='{}' value='{}'", label, val);
		    return true;
		}
	    } catch (Exception e) {
		logger.debug("selectOption by label '{}' failed: {}", label, e.getMessage());
	    }

	    // 2) Match option by text, select by value attribute
	    try {
		Locator options = select.locator("option");
		for (int i = 0; i < options.count(); i++) {
		    Locator opt = options.nth(i);
		    String text = opt.innerText().trim();
		    String value = opt.getAttribute("value");
		    if (value == null || value.isBlank()) {
			continue;
		    }
		    if (text.equalsIgnoreCase(label) || text.toLowerCase(Locale.ROOT)
			    .contains(label.toLowerCase(Locale.ROOT))) {
			select.selectOption(value);
			Thread.sleep(150);
			syncDisableReasonToAngular(page, value, text);
			logger.info("Disable Charge reason set via option value='{}' text='{}'", value, text);
			return true;
		    }
		}
	    } catch (Exception e) {
		logger.debug("option scan for '{}' failed: {}", label, e.getMessage());
	    }
	}

	// 3) Known ids from Zotec HTML
	String[] knownIds = { "1019", "1030", "1012", "1" }; // ABI only, Code Assist, Incorrect CPT, Duplicate
	for (String id : knownIds) {
	    try {
		select.selectOption(id);
		Thread.sleep(150);
		String val = select.inputValue();
		if (id.equals(val)) {
		    syncDisableReasonToAngular(page, id, null);
		    logger.info("Disable Charge reason set via known id='{}'", id);
		    return true;
		}
	    } catch (Exception ignored) {
	    }
	}
	return false;
    }

    private void syncDisableReasonToAngular(Page page, String value, String label) {
	try {
	    // Scope Select2 label to disabledReasonId's own container only.
	    // Do NOT use document.querySelector('form[name=form] .select2-chosen') —
	    // that matched Patient and overwrote it to "ABI only".
	    page.evaluate("(args) => {"
		    + "  const sel = document.querySelector(\"select[name='disabledReasonId']\");"
		    + "  if (!sel) return;"
		    + "  sel.value = args.value;"
		    + "  sel.dispatchEvent(new Event('input', { bubbles: true }));"
		    + "  sel.dispatchEvent(new Event('change', { bubbles: true }));"
		    + "  const $ = window.jQuery || window.$;"
		    + "  if ($ && $.fn && $.fn.select2) {"
		    + "    try { $(sel).val(args.value).trigger('change'); } catch (e) {}"
		    + "  }"
		    + "  const ang = window.angular;"
		    + "  if (ang) {"
		    + "    try {"
		    + "      const scope = ang.element(sel).scope();"
		    + "      if (scope) {"
		    + "        scope.disabledReasonId = args.value;"
		    + "        if (scope.$apply) scope.$apply();"
		    + "      }"
		    + "    } catch (e2) {}"
		    + "  }"
		    + "  if (!args.label) return;"
		    + "  let span = null;"
		    + "  let sib = sel.previousElementSibling;"
		    + "  while (sib && !span) {"
		    + "    if (sib.classList && sib.classList.contains('select2-container')) {"
		    + "      span = sib.querySelector('.select2-chosen');"
		    + "      break;"
		    + "    }"
		    + "    if (sib.querySelector) {"
		    + "      span = sib.querySelector('.select2-chosen');"
		    + "      if (span) break;"
		    + "    }"
		    + "    sib = sib.previousElementSibling;"
		    + "  }"
		    + "  if (!span && sel.id) {"
		    + "    const s2 = document.getElementById('s2id_' + sel.id);"
		    + "    if (s2) span = s2.querySelector('.select2-chosen');"
		    + "  }"
		    + "  if (span) span.textContent = args.label;"
		    + "}",
		    java.util.Map.of("value", value, "label", label != null ? label : value));
	} catch (Exception e) {
	    logger.warn("syncDisableReasonToAngular failed: {}", e.getMessage());
	}
    }

    private boolean waitUntilOkEnabled(Locator ok, int timeoutMs) {
	long deadline = System.currentTimeMillis() + timeoutMs;
	while (System.currentTimeMillis() < deadline) {
	    try {
		String disabled = ok.getAttribute("disabled");
		boolean ariaDisabled = "true".equalsIgnoreCase(ok.getAttribute("aria-disabled"));
		if ((disabled == null || disabled.isBlank()) && !ariaDisabled && ok.isEnabled()) {
		    return true;
		}
	    } catch (Exception ignored) {
	    }
	    try {
		Thread.sleep(150);
	    } catch (InterruptedException e) {
		Thread.currentThread().interrupt();
		return false;
	    }
	}
	return false;
    }

    private void forceEnableDisableChargeOk(Page page, Locator form) {
	try {
	    page.evaluate("() => {"
		    + "  const btns = document.querySelectorAll("
		    + "    \"form[name='form'] .modal-footer button.btn-primary, "
		    + "     .modal-footer button.btn-primary\");"
		    + "  btns.forEach(b => {"
		    + "    b.removeAttribute('disabled');"
		    + "    b.disabled = false;"
		    + "    b.classList.remove('disabled');"
		    + "  });"
		    + "  const sel = document.querySelector(\"select[name='disabledReasonId']\");"
		    + "  const ang = window.angular;"
		    + "  if (ang && sel) {"
		    + "    try {"
		    + "      const scope = ang.element(sel).scope();"
		    + "      if (scope && scope.form) {"
		    + "        scope.form.$setValidity('required', true);"
		    + "        if (scope.$apply) scope.$apply();"
		    + "      }"
		    + "    } catch (e) {}"
		    + "  }"
		    + "}");
	} catch (Exception e) {
	    logger.warn("forceEnableDisableChargeOk failed: {}", e.getMessage());
	}
    }

    private void logModalButtons(Locator modal) {
	try {
	    Locator btns = modal.locator("button, input[type='button'], input[type='submit'], a.btn");
	    int n = Math.min(btns.count(), 12);
	    for (int i = 0; i < n; i++) {
		Locator b = btns.nth(i);
		String label = "";
		try {
		    label = b.innerText();
		    if (label == null || label.isBlank()) {
			label = b.getAttribute("value");
		    }
		} catch (Exception ignored) {
		}
		logger.info("Disable modal control[{}]: visible={} disabledAttr={} text='{}'", i,
			b.isVisible(), b.getAttribute("disabled"), label);
	    }
	} catch (Exception ignored) {
	}
    }

    private Locator firstVisibleOpener(Locator row) {
	String[] selectors = {
		"a.select2-choice",
		".select2-container",
		"xpath=.//span/div/div"
	};
	for (String sel : selectors) {
	    try {
		Locator loc = row.locator(sel);
		for (int i = 0; i < loc.count(); i++) {
		    Locator c = loc.nth(i);
		    if (c.isVisible()) {
			return c;
		    }
		}
	    } catch (Exception ignored) {
	    }
	}
	return null;
    }

    private Locator visibleSelect2Search(Page page) {
	Locator candidates = page.locator(
		".select2-drop-active .select2-search input.select2-input, "
			+ ".select2-drop:not(.select2-display-none) .select2-search input.select2-input, "
			+ "input.select2-input.select2-focused, input.select2-input.select2-active");
	try {
	    for (int i = 0; i < candidates.count(); i++) {
		Locator c = candidates.nth(i);
		if (c.isVisible()) {
		    return c;
		}
	    }
	} catch (Exception ignored) {
	}
	return null;
    }

    /** Closes open Select2 dropdown / mask so it cannot intercept later clicks. */
    private void dismissSelect2(Page page) {
	try {
	    if (page.locator("#select2-drop-mask").count() > 0
		    || page.locator(".select2-drop-active, .select2-drop:not(.select2-display-none)").count() > 0) {
		page.keyboard().press("Escape");
		Thread.sleep(200);
		page.keyboard().press("Escape");
		Thread.sleep(200);
	    }
	    Locator mask = page.locator("#select2-drop-mask");
	    if (mask.count() > 0) {
		try {
		    mask.first().click(new Locator.ClickOptions().setForce(true).setTimeout(1000));
		} catch (Exception ignored) {
		}
		Thread.sleep(150);
	    }
	} catch (Exception ignored) {
	}
    }
}
