package com.wl.zotecAgent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allowlist for Select client(s). Matching is case-insensitive and supports:
 * <ul>
 *   <li>name contains either way (handles truncated UI labels with {@code ...})</li>
 *   <li>codes in parentheses, e.g. {@code (CVF1BJH)}, {@code (MEDS-102)}</li>
 * </ul>
 * <p>
 * Flow iterates {@link #orderedEntries()} in order and selects matching UI checkboxes;
 * allowlist entries with no Select client(s) match are skipped.
 */
public final class AllowedClients {

    private static final Pattern PARENS_CODE = Pattern.compile("\\(([^)]+)\\)");
    private static final Pattern LEADING_COUNT = Pattern.compile("^\\d+\\s*");

    private static final String[] RAW = {
			"TRIDENT MEDICAL CENTER IP (CRPA04)",
			"James Island Emergency (CRPA27)",
			"SECOND AVE MRI DBA ADVANCED... (SAM11)",
			"BRIGHTON PARK EMERGENCY (CRPA24)",
			"LCMA Missing Reports",
			"Charleston ENT Allergy Reports",
			"Live LCNeurosurgical Reports",
			"LAKE CUMBERLAND REGIONAL HO... (LCRHER)",
			"Randall Snyder III RL SORA (RANDA-SORA)",
			"case 02941871",
			"IR Consult",
			"SJRM - CA01 - ZPSJRMSJR - R..."
    };

    private AllowedClients() {
    }

    /**
     * Allowlist entries in declared order (same as {@code RAW}).
     * Flow/FlowText walk this list and skip entries missing from Select client(s).
     */
    public static List<String> orderedEntries() {
	return Collections.unmodifiableList(Arrays.asList(RAW));
    }

    /**
     * Index of the first UI checkbox label that matches {@code allowlistEntry},
     * skipping indexes in {@code alreadyUsed}. Returns {@code -1} if none.
     */
    public static int findMatchingUiIndex(String allowlistEntry, List<String> uiLabels,
	    Set<Integer> alreadyUsed) {
	if (allowlistEntry == null || allowlistEntry.isBlank() || uiLabels == null) {
	    return -1;
	}
	for (int i = 0; i < uiLabels.size(); i++) {
	    if (alreadyUsed != null && alreadyUsed.contains(i)) {
		continue;
	    }
	    if (matchesEntry(allowlistEntry, uiLabels.get(i))) {
		return i;
	    }
	}
	return -1;
    }

    /** Case-insensitive allow check using display text and/or checkbox id. */
    public static boolean isAllowed(String uiText) {
	if (uiText == null || uiText.isBlank()) {
	    return false;
	}
	for (String entry : RAW) {
	    if (matchesEntry(entry, uiText)) {
		return true;
	    }
	}
	return false;
    }

    /**
     * Whether one allowlist entry matches a Select client(s) checkbox label
     * (name / truncation / paren codes).
     */
    public static boolean matchesEntry(String allowlistEntry, String uiText) {
	if (allowlistEntry == null || allowlistEntry.isBlank() || uiText == null || uiText.isBlank()) {
	    return false;
	}

	Set<String> allowedCodes = extractCodes(allowlistEntry);
	for (String code : extractCodes(uiText)) {
	    if (allowedCodes.contains(code)) {
		return true;
	    }
	}
	for (String token : uiText.split("[\\s_\\-]+")) {
	    String t = token.trim().toLowerCase(Locale.ROOT);
	    if (t.length() >= 4 && allowedCodes.contains(t)) {
		return true;
	    }
	}

	String allowed = normalizeName(allowlistEntry);
	String uiName = normalizeName(uiText);
	if (allowed.isBlank() || uiName.isBlank()) {
	    return false;
	}
	if (uiName.equals(allowed) || uiName.contains(allowed) || allowed.contains(uiName)) {
	    return true;
	}
	String allowedStem = stripEllipsis(allowed);
	String uiStem = stripEllipsis(uiName);
	return !allowedStem.isBlank() && !uiStem.isBlank()
		&& (uiStem.contains(allowedStem) || allowedStem.contains(uiStem));
    }

    static String normalizeName(String raw) {
	if (raw == null) {
	    return "";
	}
	String s = raw.toLowerCase(Locale.ROOT).trim();
	s = LEADING_COUNT.matcher(s).replaceFirst("");
	s = s.replace('\u00a0', ' ');
	s = s.replaceAll("\\s+", " ").trim();
	return s;
    }

    private static String stripEllipsis(String s) {
	return s.replace("...", "").trim();
    }

    static Set<String> extractCodes(String raw) {
	Set<String> codes = new HashSet<>();
	if (raw == null) {
	    return codes;
	}
	Matcher m = PARENS_CODE.matcher(raw);
	while (m.find()) {
	    String code = m.group(1).trim().toLowerCase(Locale.ROOT);
	    if (!code.isBlank()) {
		codes.add(code);
	    }
	}
	// also "ER(COMHVHER)" without space before paren
	return codes;
    }
}
