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

/**
 * Image-based coding flow: page images → PDF upload → review → fill form.
 * Walks UI-selected clients (or {@link AllowedClients} when none provided);
 * skips entries missing from Select client(s) {@code badge-warning} checkboxes.
 * Patient looping: after fill, bot clicks Submit if enabled else Skip.
 * <p>
 * RAD portal has no ED supplemental form — after patient details, fills ICD/CPT only.
 */
@Component
public class Flow {
    public static final Logger logger = LogManager.getLogger(Flow.class);
    public static Map<String, Object> patientInfo = new LinkedHashMap<>();
    static Date d = new Date();
    static SimpleDateFormat f = new SimpleDateFormat("MM-dd-yyyy");
    static String date = f.format(d);
    public static boolean isaides = false;
    static String PatientDOB = "";
    public static LinkedHashMap<String, String> ExcelObj = new LinkedHashMap<>();
    static String accountNumber;

    /** Image Flow uses orange badge-warning (image report counts). */
    private static final String LOCATION_BADGE = ClientLocationSelector.BADGE_WARNING;
    private static final String SELECT_CLIENTS_TOGGLE =
	    "a.dropdown-toggle[ng-click*='refreshLocationFilter']";
    private static final String NO_MORE_REPORTS =
	    "There are no more reports to view based on your filters";
    private static final String REPORT_COMPLETED = "This report has been completed.";
    private static final String DATA_LOCKED_TITLE = "Data Locked";
    private static final String DATA_LOCKED_BODY =
	    "The data cannot be submitted because it is locked for edit by another user";
    /** Image charts: refresh hospital review UI twice after upload. */
    private static final int REVIEW_UI_REFRESH_COUNT = 2;

    private final ZotecService zs;
    private final DocumentProcessingService documentProcessing;
    private final HospitalReviewUiSession reviewUi;

    @Autowired
    public Flow(ZotecService zs, DocumentProcessingService documentProcessing,
	    HospitalReviewUiSession reviewUi) {
	this.zs = zs;
	this.documentProcessing = documentProcessing;
	this.reviewUi = reviewUi;
    }

    public void Start(BrowserContext context, String agentId) throws Exception {
	Start(context, agentId, null);
    }

    /**
     * @param selectedClients client labels from the frontend UI; when null/empty falls back to
     *                        {@link AllowedClients#orderedEntries()}
     */
    public void Start(BrowserContext context, String agentId, List<String> selectedClients)
	    throws Exception {
	Page page = resolveWorkfilePage(context);
	try {
	    PlaywrightService ps = new PlaywrightService(page);

	    if (isSelectClientsToggleVisible(page)) {
		logger.info("Skipped Zotec login — Select client(s) already visible");
		Thread.sleep(1000);
	    } else {
		zs.login(page);
		Thread.sleep(5000);
	    }

	    ClientLocationSelector.openClientSelector(ps, page, LOCATION_BADGE);
	    List<String> uiLabels = ClientLocationSelector.collectUiLabels(page, LOCATION_BADGE);
	    logger.info("Found {} image-badge client checkbox(es) in Select client(s)", uiLabels.size());

	    List<String> allowlist = (selectedClients != null && !selectedClients.isEmpty())
		    ? selectedClients
		    : AllowedClients.orderedEntries();
	    java.util.Set<Integer> usedUiIndexes = new java.util.HashSet<>();
	    logger.info("Walking {} client entr(y/ies) from UI/allowlist (skip if missing from portal)",
		    allowlist.size());

	    for (int a = 0; a < allowlist.size(); a++) {
		String allowEntry = allowlist.get(a);
		int clientIndex = AllowedClients.findMatchingUiIndex(allowEntry, uiLabels, usedUiIndexes);
		if (clientIndex < 0) {
		    logger.info("Allowlist [{}/{}]: '{}' — not in Select client(s), skipping",
			    a + 1, allowlist.size(), allowEntry);
		    continue;
		}
		usedUiIndexes.add(clientIndex);
		String locationKey = ClientLocationSelector.locationKeyAt(page, LOCATION_BADGE, clientIndex);
		logger.info("Allowlist [{}/{}]: '{}' — matched UI checkbox [{}] '{}' key={}",
			a + 1, allowlist.size(), allowEntry, clientIndex, uiLabels.get(clientIndex),
			locationKey);

		String selectedClientLocation;
		try {
		    selectedClientLocation = ClientLocationSelector.selectOnlyAndApply(ps, page,
			    LOCATION_BADGE, locationKey);
		} catch (Exception e) {
		    logger.warn("Could not select location '{}' — skipping: {}", allowEntry, e.getMessage());
		    continue;
		}
		logger.info("Selected client_location for upload metadata: {}", selectedClientLocation);

		int patientIndex = 0;
		String previousFingerprint = null;

		while (true) {
		    if (hasNoMoreReportsMessage(page)) {
			logger.info("UI: no more reports for allowlist '{}' — next location", allowEntry);
			break;
		    }

		    dismissDataLockedIfPresent(page);

		    patientIndex++;
		    logger.info("--- Patient #{} under '{}' (image/PDF) ---", patientIndex, allowEntry);

		    if (!waitForPatientImagesReady(page)) {
			if (hasNoMoreReportsMessage(page)) {
			    logger.info("No more reports while waiting for images — next location");
			    break;
			}
			logger.warn("Still no page images — retrying on same location");
			Thread.sleep(3000);
			patientIndex--;
			continue;
		    }

		    String fingerprint = pageImageFingerprint(page);
		    if (fingerprint == null || fingerprint.isBlank()) {
			logger.warn("Empty image fingerprint — retrying on same location");
			Thread.sleep(3000);
			patientIndex--;
			continue;
		    }

		    if (previousFingerprint != null && previousFingerprint.equals(fingerprint)) {
			logger.warn(
				"Page images unchanged — dismissing Data Locked if any and Skip again (stay on location)");
			dismissDataLockedIfPresent(page);
			SkipAdvanceResult stuck = clickSkipAndWaitForNext(ps, page, previousFingerprint);
			if (stuck == SkipAdvanceResult.NO_MORE_REPORTS) {
			    break;
			}
			continue;
		    }
		    previousFingerprint = fingerprint;

		    if (hasReportCompletedMessage(page)) {
			logger.info("UI: This report has been completed — Skip to next patient ('{}')",
				allowEntry);
			SkipAdvanceResult completedSkip = clickSkipAndWaitForNext(ps, page,
				previousFingerprint);
			if (completedSkip == SkipAdvanceResult.NO_MORE_REPORTS) {
			    break;
			}
			continue;
		    }

		    boolean processed = processOnePatient(page, selectedClientLocation);
		    if (!processed) {
			logger.error("Patient #{} failed — clicking Skip if possible", patientIndex);
		    }

		    SkipAdvanceResult advance = clickSubmitOrSkipAndWaitForNext(ps, page, previousFingerprint);
		    if (advance == SkipAdvanceResult.NO_MORE_REPORTS) {
			logger.info("No more patients for '{}' — next allowlist location", allowEntry);
			break;
		    }
		    if (advance == SkipAdvanceResult.TIMEOUT) {
			logger.warn(
				"Submit/Skip advance timed out — stay on '{}'; will retry next loop",
				allowEntry);
		    }
		}
	    }

	    logger.info("All selected/allowlist locations processed (image Flow)");
	    page.pause();

	} catch (Exception e) {
	    e.printStackTrace();
	    page.pause();
	}
    }

    /**
     * Prefer an existing tab that already shows the Select client(s) toggle;
     * otherwise open a new page for login / navigate.
     */
    private Page resolveWorkfilePage(BrowserContext context) {
	for (Page existing : context.pages()) {
	    try {
		Locator toggle = existing.locator(SELECT_CLIENTS_TOGGLE).first();
		if (toggle.count() > 0 && toggle.isVisible()) {
		    logger.info("Reusing existing tab with Select client(s) toggle visible");
		    existing.bringToFront();
		    return existing;
		}
	    } catch (Exception e) {
		// try next tab
	    }
	}
	return context.newPage();
    }

    private boolean isSelectClientsToggleVisible(Page page) {
	try {
	    Locator toggle = page.locator(SELECT_CLIENTS_TOGGLE).first();
	    return toggle.count() > 0 && toggle.isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    /**
     * Collect page images → PDF upload → hospital review UI → poll resume → fill form.
     */
    private boolean processOnePatient(Page page, String selectedClientLocation)
	    throws Exception {
	if (hasReportCompletedMessage(page)) {
	    logger.info("This report has been completed — skipping fill");
	    return true;
	}

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked dismissed at start of patient — treat as skip to next");
	    return true;
	}

	Map<String, Object> uploadMetadata = WorkfileSummaryScraper.build(page, selectedClientLocation);
	Map<String, Object> uploadMeta = documentProcessing.uploadPdfAndAwaitResume(page, uploadMetadata,
		() -> {
		    reviewUi.ensureOpenAndLoggedIn(page.context());
		    reviewUi.refreshAwaitStartAndSubmitReview(REVIEW_UI_REFRESH_COUNT);
		    page.bringToFront();
		});

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
	patientInfo.put("pdf_path", uploadMeta.get("pdf_path"));
	patientInfo.put("resume_payload", resumePayload);

	logger.info("Resume payload received for document_id={}", uploadMeta.get("document_id"));

	page.bringToFront();
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
		s.validateCPT(page, cptEntries, icdList);
		s.validateICD(icdList, page);
		CodingFormValidationService formSvc = new CodingFormValidationService(page);
		formSvc.updateBillingExtras(patientInfo);
		formSvc.updateIssueOrRfi(patientInfo);

	ZtecVerifierGate.dismissYesIDidIfPresent(page);

	if (dismissDataLockedIfPresent(page)) {
	    logger.info("Data Locked after CPT/ICD — OK clicked, move to next patient");
	}
	return true;
    }

    private enum SkipAdvanceResult {
	NEXT_PATIENT, NO_MORE_REPORTS, TIMEOUT
    }

    /** Wait until at least one decodeable page image is present (or no-more-reports). */
    private boolean waitForPatientImagesReady(Page page) throws InterruptedException {
	for (int attempt = 0; attempt < 120; attempt++) {
	    if (hasNoMoreReportsMessage(page)) {
		return false;
	    }
	    dismissDataLockedIfPresent(page);
	    String fp = pageImageFingerprint(page);
	    if (fp != null && !fp.isBlank()) {
		return true;
	    }
	    Thread.sleep(1000);
	}
	logger.warn("Timed out waiting for page images (staying on same checkbox)");
	return false;
    }

    /**
     * Fingerprint of current report images (first few img src prefixes) to detect patient change.
     */
    private String pageImageFingerprint(Page page) {
	try {
	    Locator imgs = page.locator("//img");
	    int count = imgs.count();
	    if (count == 0) {
		return "";
	    }
	    StringBuilder sb = new StringBuilder();
	    int n = Math.min(count, 5);
	    for (int i = 0; i < n; i++) {
		String src = String.valueOf(imgs.nth(i).getAttribute("src"));
		if (src == null || src.isBlank() || "null".equals(src)) {
		    continue;
		}
		sb.append(src, 0, Math.min(120, src.length())).append("|");
	    }
	    return sb.toString();
	} catch (Exception e) {
	    return "";
	}
    }

    /**
     * After fill: click Submit if enabled, otherwise Skip. Waits for next patient fingerprint.
     */
    private SkipAdvanceResult clickSubmitOrSkipAndWaitForNext(PlaywrightService ps, Page page,
	    String previousFingerprint) throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	page.bringToFront();
	dismissDataLockedIfPresent(page);
	ZtecVerifierGate.dismissYesIDidThenRevealSubmitSkip(page);

	Locator submitBtn = page.getByRole(AriaRole.BUTTON,
		new Page.GetByRoleOptions().setName("Submit").setExact(true));
	boolean submitEnabled = false;
	try {
	    submitEnabled = submitBtn.count() > 0 && submitBtn.first().isEnabled()
		    && submitBtn.first().isVisible();
	} catch (Exception e) {
	    logger.warn("Could not read Submit enabled state: {}", e.getMessage());
	}

	if (submitEnabled) {
	    logger.info("Submit enabled — clicking Submit → next patient");
	    ZtecVerifierGate.dismissYesIDidIfPresent(page);
	    ps.click(submitBtn.first(), "Submit → next patient");
	    Thread.sleep(3000);
	    return waitForFingerprintAdvance(page, previousFingerprint, "Submit");
	}

	logger.info("Submit missing/disabled — clicking Skip");
	return clickSkipAndWaitForNext(ps, page, previousFingerprint);
    }

    private SkipAdvanceResult waitForFingerprintAdvance(Page page, String previousFingerprint, String action)
	    throws InterruptedException {
	for (int attempt = 0; attempt < 60; attempt++) {
	    if (dismissDataLockedIfPresent(page)) {
		logger.info("Data Locked after {} — OK clicked; waiting for next patient", action);
		Thread.sleep(2000);
		continue;
	    }
	    if (hasNoMoreReportsMessage(page)) {
		logger.info("No more reports after {}", action);
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    try {
		String fp = pageImageFingerprint(page);
		if (fp != null && !fp.isBlank() && !fp.equals(previousFingerprint)) {
		    return SkipAdvanceResult.NEXT_PATIENT;
		}
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(1000);
	}
	logger.warn("Timed out waiting for next patient after {} — stay on checkbox", action);
	return SkipAdvanceResult.TIMEOUT;
    }

    private SkipAdvanceResult clickSkipAndWaitForNext(PlaywrightService ps, Page page, String previousFingerprint)
	    throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	dismissDataLockedIfPresent(page);
	ZtecVerifierGate.dismissYesIDidThenRevealSubmitSkip(page);

	Locator skipBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Skip"));
	if (skipBtn.count() == 0 || !skipBtn.first().isEnabled()) {
	    ZtecVerifierGate.dismissYesIDidIfPresent(page);
	    skipBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Skip"));
	}
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
	return waitForFingerprintAdvance(page, previousFingerprint, "Skip");
    }

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

    private boolean hasNoMoreReportsMessage(Page page) {
	try {
	    Locator banner = page.getByText(NO_MORE_REPORTS);
	    return banner.count() > 0 && banner.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
    }

    private boolean hasReportCompletedMessage(Page page) {
	try {
	    Locator banner = page.getByText(REPORT_COMPLETED);
	    return banner.count() > 0 && banner.first().isVisible();
	} catch (Exception e) {
	    return false;
	}
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
