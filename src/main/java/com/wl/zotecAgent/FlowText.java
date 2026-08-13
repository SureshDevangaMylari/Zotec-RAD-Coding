package com.wl.zotecAgent;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.wl.util.FileUtil;
import com.wl.util.PlaywrightService;

//   "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\playwright"
/**
 * Text-based coding flow. Walks each Select client(s) location that shows a blue
 * {@code badge-info} report count — one checkbox at a time; unchecks when done.
 * RAD portal has no ED supplemental form — after patient details, fills ICD/CPT only.
 */
@Component
public class FlowText {
    public static final Logger logger = LogManager.getLogger(FlowText.class);
    public static Map<String, Object> patientInfo = new LinkedHashMap<>();
    static Date d = new Date();
    static SimpleDateFormat f = new SimpleDateFormat("MM-dd-yyyy");
    static String date = f.format(d);
    public static boolean isaides = false;
    static String PatientDOB = "";
    public static LinkedHashMap<String, String> ExcelObj = new LinkedHashMap<>();
    static String accountNumber;

    /** Text Flow uses blue badge-info (non-image report counts). */
    private static final String LOCATION_BADGE = ClientLocationSelector.BADGE_INFO;
    private static final String NO_MORE_REPORTS =
	    "There are no more reports to view based on your filters";
    private static final String REPORT_COMPLETED = "This report has been completed.";
    private static final String DATA_LOCKED_TITLE = "Data Locked";
    private static final String DATA_LOCKED_BODY =
	    "The data cannot be submitted because it is locked for edit by another user";

    private final ZotecService zs;
    private final DocumentProcessingService documentProcessing;

    @Autowired
    public FlowText(ZotecService zs, DocumentProcessingService documentProcessing) {
	this.zs = zs;
	this.documentProcessing = documentProcessing;
    }

    public void Start(BrowserContext context, String agentId) throws Exception {
	Page page = context.newPage();
	try {
	    PlaywrightService ps = new PlaywrightService(page);

	    zs.login(page);
	    Thread.sleep(5000);

	    ClientLocationSelector.openClientSelector(ps, page, LOCATION_BADGE);
	    List<String> locationKeys = ClientLocationSelector.collectLocationKeys(page, LOCATION_BADGE);
	    logger.info("Found {} blue-badge location checkbox(es) with {}", locationKeys.size(),
		    LOCATION_BADGE);

	    for (int li = 0; li < locationKeys.size(); li++) {
		String locationKey = locationKeys.get(li);
		logger.info("Location [{}/{}]: selecting '{}'", li + 1, locationKeys.size(), locationKey);

		String selectedClientLocation;
		try {
		    selectedClientLocation = ClientLocationSelector.selectOnlyAndApply(ps, page,
			    LOCATION_BADGE, locationKey);
		} catch (Exception e) {
		    logger.warn("Could not select location '{}' — skipping: {}", locationKey, e.getMessage());
		    continue;
		}
		logger.info("Selected client_location for upload metadata: {}", selectedClientLocation);

		int patientIndex = 0;
		String previousTextFingerprint = null;

		while (true) {
		    // ONLY leave this location when the empty-queue banner is shown
		    if (hasNoMoreReportsMessage(page)) {
			logger.info("UI: no more reports for '{}' — uncheck and next location",
				locationKey);
			break;
		    }

		    dismissDataLockedIfPresent(page);

		    patientIndex++;
		    logger.info("--- Patient #{} under '{}' ---", patientIndex, locationKey);

		    Locator reportLoc = page.locator("//*[@id='dictated-report-text']");
		    String text = waitForDictatedReportText(ps, page, reportLoc);
		    if (hasNoMoreReportsMessage(page)) {
			logger.info("No more reports while waiting for dictated text — next location");
			break;
		    }
		    if (text == null || text.isBlank()) {
			logger.warn("Still no dictated text — retrying on same location (not advancing)");
			Thread.sleep(3000);
			patientIndex--; // don't inflate count on empty waits
			continue;
		    }

		    String fingerprint = textFingerprint(text);
		    if (previousTextFingerprint != null && previousTextFingerprint.equals(fingerprint)) {
			logger.warn(
				"Dictated text unchanged — dismissing Data Locked if any and Skip again (stay on location)");
			dismissDataLockedIfPresent(page);
			SkipAdvanceResult stuck = clickSkipAndWaitForNext(ps, page, previousTextFingerprint);
			if (stuck == SkipAdvanceResult.NO_MORE_REPORTS) {
			    break;
			}
			continue;
		    }
		    previousTextFingerprint = fingerprint;

		    if (hasReportCompletedMessage(page)) {
			logger.info("UI: This report has been completed — Skip to next patient ('{}')",
				locationKey);
			SkipAdvanceResult completedSkip = clickSkipAndWaitForNext(ps, page,
				previousTextFingerprint);
			if (completedSkip == SkipAdvanceResult.NO_MORE_REPORTS) {
			    break;
			}
			continue;
		    }

		    boolean processed = processOnePatient(page, text, selectedClientLocation);
		    if (!processed) {
			logger.error("Patient #{} failed — waiting for manual Submit/Skip", patientIndex);
		    }

		    SkipAdvanceResult advance = waitForManualSubmitOrSkipAndNext(ps, page,
			    previousTextFingerprint);
		    if (advance == SkipAdvanceResult.NO_MORE_REPORTS) {
			logger.info("No more patients for '{}' — uncheck and next location", locationKey);
			break;
		    }
		    if (advance == SkipAdvanceResult.TIMEOUT) {
			logger.warn(
				"Manual Submit/Skip wait timed out — stay on '{}'; will retry next loop",
				locationKey);
		    }
		}

		try {
		    ClientLocationSelector.uncheckAllAndApply(ps, page, LOCATION_BADGE);
		    logger.info("Unchecked location '{}' after patients completed", locationKey);
		} catch (Exception e) {
		    logger.warn("Could not uncheck location '{}': {}", locationKey, e.getMessage());
		}
	    }

	    logger.info("All blue badge-info locations processed");
	    page.pause();

	} catch (Exception e) {
	    e.printStackTrace();
	    page.pause();
	}
    }

    /**
     * Upload dictated text (with well + client_location metadata) and fill form.
     */
    private boolean processOnePatient(Page page, String text,
	    String selectedClientLocation) throws Exception {
	if (hasReportCompletedMessage(page)) {
	    logger.info("This report has been completed — skipping fill");
	    return true;
	}

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked dismissed at start of patient — treat as skip to next");
	    return true;
	}

	Map<String, Object> uploadMetadata = WorkfileSummaryScraper.build(page, selectedClientLocation);
	Map<String, Object> uploadMeta = documentProcessing.uploadTextAndAwaitResume(text, uploadMetadata);

	@SuppressWarnings("unchecked")
	Map<String, Object> resumePayload = uploadMeta.get("resume_payload") instanceof Map
		? (Map<String, Object>) uploadMeta.get("resume_payload")
		: Map.of();

	if (resumePayload.isEmpty()) {
	    logger.error("No resume payload received (document_id={}, errors={})",
		    uploadMeta.get("document_id"), uploadMeta.get("resume_error"));
	    return false;
	}

	patientInfo = ResumePayloadMapper.toValidationMap(resumePayload);
	patientInfo.put("document_id", uploadMeta.get("document_id"));
	patientInfo.put("batch_id", uploadMeta.get("document_id"));
	patientInfo.put("text_path", uploadMeta.get("text_path"));
	patientInfo.put("resume_payload", resumePayload);

	logger.info("Resume payload received for document_id={}", uploadMeta.get("document_id"));

	zs.validatePatientDetails(page, patientInfo);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after patient details — OK clicked, move to next patient");
	    return true;
	}

	// RAD Zotec portal has no ED form — skip ED open/fill/submit; go straight to ICD/CPT.
	Service s = new Service();
	List<Map<String, Object>> cptEntries = ResumePayloadMapper.extractCptEntries(resumePayload);
	List<String> icdList = ResumePayloadMapper.extractIcdCodeList(resumePayload);

	logger.info("validateCPT entries: {}", cptEntries);
	logger.info("validateICD codes: {}", icdList);

	//s.validateICD(icdList, page);
	// Accident Date/Type often appear only after an accident ICD is on the form
	    s.validateCPT(page, cptEntries, icdList);
		s.validateICD(icdList, page);
		new CodingFormValidationService(page).updateBillingExtras(patientInfo);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after CPT/ICD — OK clicked, move to next patient");
	}
	return true;
    }

    private enum SkipAdvanceResult {
	NEXT_PATIENT, NO_MORE_REPORTS, TIMEOUT
    }

    /**
     * Wait for dictated report text. Does not advance checkbox — only returns when text is ready,
     * "no more reports" is shown, or a long poll expires (caller stays on same checkbox).
     */
    private String waitForDictatedReportText(PlaywrightService ps, Page page, Locator reportLoc)
	    throws InterruptedException {
	for (int attempt = 0; attempt < 120; attempt++) {
	    if (hasNoMoreReportsMessage(page)) {
		return null;
	    }
	    dismissDataLockedIfPresent(page);
	    try {
		if (reportLoc.count() > 0 && reportLoc.first().isVisible()) {
		    String text = ps.getText(reportLoc, "getting text");
		    if (text != null && !text.isBlank()) {
			return text;
		    }
		}
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(1000);
	}
	logger.warn("Timed out waiting for dictated report text (staying on same checkbox)");
	return null;
    }

    /**
     * Do not click Submit/Skip — wait until the user clicks either button manually.
     * Detects advance when dictated report text changes or the empty-queue banner appears.
     */
    private SkipAdvanceResult waitForManualSubmitOrSkipAndNext(PlaywrightService ps, Page page,
	    String previousFingerprint) throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	dismissDataLockedIfPresent(page);

	logger.info(
		"Waiting for USER to click Submit or Skip manually — bot will not click either button");

	Locator reportLoc = page.locator("//*[@id='dictated-report-text']");
	// Up to ~60 minutes for manual review
	for (int attempt = 0; attempt < 3600; attempt++) {
	    if (dismissDataLockedIfPresent(page)) {
		logger.info("Data Locked while waiting for manual Submit/Skip — OK clicked; continue waiting");
		Thread.sleep(2000);
		continue;
	    }
	    if (hasNoMoreReportsMessage(page)) {
		logger.info("No more reports after manual Submit/Skip");
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    try {
		if (reportLoc.count() > 0 && reportLoc.first().isVisible()) {
		    String next = ps.getText(reportLoc, "dictated text after manual action");
		    if (next != null && !next.isBlank()) {
			String fp = textFingerprint(next);
			if (previousFingerprint == null || !fp.equals(previousFingerprint)) {
			    logger.info("Next patient detected after manual Submit/Skip");
			    return SkipAdvanceResult.NEXT_PATIENT;
			}
		    }
		}
	    } catch (Exception ignored) {
	    }
	    if (attempt > 0 && attempt % 30 == 0) {
		logger.info("Still waiting for manual Submit/Skip... ({}s)", attempt);
	    }
	    Thread.sleep(1000);
	}

	logger.warn("Timed out waiting for manual Submit/Skip — stay on checkbox (do not advance)");
	return SkipAdvanceResult.TIMEOUT;
    }

    /**
     * Click bottom Skip and wait for the next patient report.
     * {@link SkipAdvanceResult#NO_MORE_REPORTS} is the only signal to leave the checkbox.
     * Timeout / Data Locked → dismiss OK and keep trying; do not advance checkbox.
     */
    private SkipAdvanceResult clickSkipAndWaitForNext(PlaywrightService ps, Page page, String previousFingerprint)
	    throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	dismissDataLockedIfPresent(page);

	Locator skipBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Skip"));
	if (skipBtn.count() == 0 || !skipBtn.first().isEnabled()) {
	    if (hasNoMoreReportsMessage(page)) {
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    logger.info("Skip button missing or disabled — waiting (not leaving checkbox)");
	    Thread.sleep(3000);
	    dismissDataLockedIfPresent(page);
	    return SkipAdvanceResult.TIMEOUT;
	}

	ps.click(skipBtn.first(), "Skip → next patient");
	Thread.sleep(3000);

	Locator reportLoc = page.locator("//*[@id='dictated-report-text']");
	for (int attempt = 0; attempt < 60; attempt++) {
	    if (dismissDataLockedIfPresent(page)) {
		logger.info("Data Locked after Skip — OK clicked; waiting for page refresh / next patient");
		Thread.sleep(2000);
		continue;
	    }
	    if (hasNoMoreReportsMessage(page)) {
		logger.info("No more reports after Skip");
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    try {
		if (reportLoc.count() > 0 && reportLoc.first().isVisible()) {
		    String next = ps.getText(reportLoc, "next dictated text");
		    if (next != null && !next.isBlank()) {
			String fp = textFingerprint(next);
			if (!fp.equals(previousFingerprint)) {
			    return SkipAdvanceResult.NEXT_PATIENT;
			}
		    }
		}
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(1000);
	}

	logger.warn("Timed out waiting for next patient after Skip — stay on checkbox (do not advance)");
	return SkipAdvanceResult.TIMEOUT;
    }

    /**
     * If the "Data Locked" dialog is visible, click OK (page refreshes). Returns true if dismissed.
     */
    private boolean dismissDataLockedIfPresent(Page page) {
	try {
	    Locator body = page.getByText(DATA_LOCKED_BODY);
	    Locator title = page.getByText(DATA_LOCKED_TITLE);
	    boolean visible = (body.count() > 0 && body.first().isVisible())
		    || (title.count() > 0 && title.first().isVisible());
	    if (!visible) {
		return false;
	    }
	    logger.info("Data Locked dialog detected — clicking OK");
	    Locator ok = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("OK"));
	    if (ok.count() == 0) {
		ok = page.locator("button:has-text('OK'), .modal button.btn-primary").first();
	    }
	    if (ok.count() > 0) {
		ok.first().click(new Locator.ClickOptions().setForce(true));
	    } else {
		page.keyboard().press("Escape");
	    }
	    Thread.sleep(2000);
	    return true;
	} catch (Exception e) {
	    logger.warn("dismissDataLockedIfPresent: {}", e.getMessage());
	    return false;
	}
    }

    /** True when the empty-queue banner is visible. */
    private boolean hasNoMoreReportsMessage(Page page) {
	try {
	    Locator banner = page.getByText(NO_MORE_REPORTS);
	    return banner.count() > 0 && banner.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    /** True when the current report is already completed in the UI. */
    private boolean hasReportCompletedMessage(Page page) {
	try {
	    Locator banner = page.getByText(REPORT_COMPLETED);
	    return banner.count() > 0 && banner.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private static String textFingerprint(String text) {
	String t = text.trim();
	return t.substring(0, Math.min(200, t.length()));
    }

    static void saveFile() throws IOException, InterruptedException {
	String[] headers = { "Processed Date", "Provider Name", "Account Number", "Patient Name", "DOS", "Work Status",
		"Exception Reason", "Eligibility Cheked", "Eligibility Status", "CPT", "Duration", "Units Created",
		"Patient Balance Amount $", "Payment Link Sent" };

	FileUtil.create("MetroOutNew", headers);
	System.out.println(ExcelObj);
	FileUtil.addRow(ExcelObj, "MetroOutNew");
	FileUtil.PrintJson(ExcelObj, ExcelObj.get("Patient Name") + "_" + ExcelObj.get("DOS"));
	Thread.sleep(1000);
    }

    static void createOrder() {
	ExcelObj.put("Processed Date", "");
	ExcelObj.put("Provider Name", "");
	ExcelObj.put("Account Number", "");
	ExcelObj.put("Patient Name", "");
	ExcelObj.put("DOS", "");
	ExcelObj.put("Work Status", "");
	ExcelObj.put("Exception Reason", "");
	ExcelObj.put("Eligibility Cheked", "");
	ExcelObj.put("Eligibility Status", "");
	ExcelObj.put("CPT", "");
	ExcelObj.put("Duration", "");
	ExcelObj.put("Units Created", "");
	ExcelObj.put("Patient Balance Amount $", "");
	ExcelObj.put("Payment Link Sent", "");
    }

    static void loadOff(Page page) {
	while (true) {
	    try {
		String display = page.locator("#LoadingPanelAction").getAttribute("style");
		System.out.println(" =========== " + display);
		if (display.contains("none")) {
		    System.out.println("loaded ");
		    Thread.sleep(1000);
		    break;
		}
	    } catch (Exception e) {
		System.out.println("error");
	    }
	    try {
		Thread.sleep(1000);
	    } catch (InterruptedException e) {
		e.printStackTrace();
	    }
	}
    }
}
