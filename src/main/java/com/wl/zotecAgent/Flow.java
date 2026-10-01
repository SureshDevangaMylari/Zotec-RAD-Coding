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
    private static final String VALIDATION_ERROR_TITLE = "Validation Error";
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
     * @param selectedClients client labels from localhost:8080 Start Agent (required; no dropdown scrape)
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

	    if (selectedClients == null || selectedClients.isEmpty()) {
		throw new IllegalStateException(
			"No clients from frontend — select clients on localhost:8080 and Start Agent");
	    }
	    logger.info("Walking {} frontend-selected client(s) (no Select client(s) collection)",
		    selectedClients.size());

	    for (int a = 0; a < selectedClients.size(); a++) {
		String frontendClient = selectedClients.get(a);
		logger.info("Frontend client [{}/{}]: '{}'", a + 1, selectedClients.size(), frontendClient);

		String selectedClientLocation;
		try {
		    selectedClientLocation = ClientLocationSelector.selectOnlyAndApplyMatchingEntry(ps,
			    page, LOCATION_BADGE, frontendClient);
		} catch (Exception e) {
		    logger.warn("Could not select frontend client '{}' — skipping: {}", frontendClient,
			    e.getMessage());
		    continue;
		}
		logger.info("Selected client_location for upload metadata: {}", selectedClientLocation);

		int patientIndex = 0;
		String previousFingerprint = null;

		while (true) {
		    if (hasNoMoreReportsMessage(page)) {
			logger.info("UI: no more reports for frontend client '{}' — next client",
				frontendClient);
			break;
		    }

		    dismissDataLockedIfPresent(page);

		    patientIndex++;
		    logger.info("--- Patient #{} under '{}' (image/PDF) ---", patientIndex, frontendClient);

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
				frontendClient);
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
			logger.info("No more patients for '{}' — next frontend client", frontendClient);
			break;
		    }
		    if (advance == SkipAdvanceResult.TIMEOUT) {
			logger.warn(
				"Submit/Skip advance timed out — stay on '{}'; will retry next loop",
				frontendClient);
		    }
		}
	    }

	    logger.info("All frontend-selected clients processed (image Flow)");
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
		s.applyCptDiagnosesFromJson(page, cptEntries, icdList);
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
     * After fill: do not click Submit/Skip — wait for the USER.
     * Disabled for now (re-enable later): blank Accident Date, both buttons disabled,
     * Override Edit, Validation Error.
     */
    private SkipAdvanceResult clickSubmitOrSkipAndWaitForNext(PlaywrightService ps, Page page,
	    String previousFingerprint) throws InterruptedException {
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}

	page.bringToFront();
	dismissDataLockedIfPresent(page);
	ZtecVerifierGate.dismissYesIDidThenRevealSubmitSkip(page);

	// TODO enable later: blank Accident Date → refresh next chart
	// if (isAccidentDateBlank(page)) {
	//     logger.info("Accident Date is blank — refreshing page for next chart (skip Submit/Skip)");
	//     return refreshPageForNextChart(page, previousFingerprint, "blank Accident Date");
	// }

	// TODO enable later: both Submit and Skip disabled → refresh next chart
	// boolean submitEnabled = isButtonEnabled(page, "Submit", true);
	// boolean skipEnabled = isButtonEnabled(page, "Skip", false);
	// if (!submitEnabled && !skipEnabled) {
	//     logger.info("Submit and Skip both disabled — refreshing page for next chart");
	//     return refreshPageForNextChart(page, previousFingerprint, "both Submit/Skip disabled");
	// }

	logger.info(
		"Waiting for USER to click Submit or Skip on Zotec — bot will not click either button");
	return waitForManualSubmitOrSkipAdvance(page, previousFingerprint);
    }

    /**
     * Long wait (~60 min) until chart advances after the user clicks Submit or Skip.
     */
    private SkipAdvanceResult waitForManualSubmitOrSkipAdvance(Page page, String previousFingerprint)
	    throws InterruptedException {
	for (int attempt = 0; attempt < 3600; attempt++) {
	    // TODO enable later: Override Edit & Confirm while waiting
	    // if (handleOverrideEditAndConfirm(page, 1)) {
	    //     logger.info("Override Edit handled while waiting for manual Submit/Skip");
	    //     Thread.sleep(2000);
	    //     continue;
	    // }
	    // TODO enable later: Validation Error → OK + refresh
	    // if (handleValidationErrorByRefresh(page)) {
	    //     logger.info("Validation Error while waiting — refreshed; waiting for next chart");
	    //     Thread.sleep(2000);
	    //     continue;
	    // }
	    if (dismissDataLockedIfPresent(page)) {
		logger.info("Data Locked while waiting — OK clicked; continue waiting");
		ZtecVerifierGate.dismissYesIDidIfPresent(page);
		Thread.sleep(2000);
		continue;
	    }
	    ZtecVerifierGate.dismissYesIDidIfPresent(page);
	    if (hasNoMoreReportsMessage(page)) {
		logger.info("No more reports after manual Submit/Skip");
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    try {
		String fp = pageImageFingerprint(page);
		if (fp != null && !fp.isBlank()
			&& (previousFingerprint == null || !fp.equals(previousFingerprint))) {
		    logger.info("Next patient detected after manual Submit/Skip (image fingerprint changed)");
		    return SkipAdvanceResult.NEXT_PATIENT;
		}
	    } catch (Exception ignored) {
	    }
	    if (attempt > 0 && attempt % 30 == 0) {
		logger.info("Still waiting for manual Submit/Skip... ({}s)", attempt);
	    }
	    Thread.sleep(1000);
	}
	logger.warn("Timed out waiting for manual Submit/Skip — stay on checkbox");
	return SkipAdvanceResult.TIMEOUT;
    }

    private SkipAdvanceResult waitForFingerprintAdvance(Page page, String previousFingerprint, String action)
	    throws InterruptedException {
	for (int attempt = 0; attempt < 60; attempt++) {
	    // TODO enable later: Override Edit & Confirm
	    // if (handleOverrideEditAndConfirm(page, 1)) {
	    //     logger.info("Override Edit handled after {} — waiting for next chart", action);
	    //     Thread.sleep(2000);
	    //     continue;
	    // }
	    // TODO enable later: Validation Error → OK + refresh
	    // if (handleValidationErrorByRefresh(page)) {
	    //     logger.info("Validation Error after {} — refreshed; waiting for next chart", action);
	    //     Thread.sleep(2000);
	    //     continue;
	    // }
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

	// TODO enable later: blank Accident Date → refresh next chart
	// if (isAccidentDateBlank(page)) {
	//     logger.info("Accident Date is blank before Skip — refreshing page for next chart");
	//     return refreshPageForNextChart(page, previousFingerprint, "blank Accident Date");
	// }

	Locator skipBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Skip"));
	if (skipBtn.count() == 0 || !skipBtn.first().isEnabled()) {
	    ZtecVerifierGate.dismissYesIDidIfPresent(page);
	    skipBtn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Skip"));
	}
	if (skipBtn.count() == 0 || !skipBtn.first().isEnabled()) {
	    if (hasNoMoreReportsMessage(page)) {
		return SkipAdvanceResult.NO_MORE_REPORTS;
	    }
	    // TODO enable later: Skip disabled → refresh next chart
	    // logger.info("Skip button missing or disabled — refreshing for next chart");
	    // return refreshPageForNextChart(page, previousFingerprint, "Skip disabled");
	    logger.info("Skip button missing or disabled — waiting (not leaving checkbox)");
	    Thread.sleep(3000);
	    dismissDataLockedIfPresent(page);
	    return SkipAdvanceResult.TIMEOUT;
	}

	ps.click(skipBtn.first(), "Skip → next patient");
	Thread.sleep(3000);
	// TODO enable later: Validation Error after Skip
	// if (handleValidationErrorByRefresh(page)) {
	//     return waitForFingerprintAdvance(page, previousFingerprint, "Validation Error refresh");
	// }
	return waitForFingerprintAdvance(page, previousFingerprint, "Skip");
    }

    private boolean isButtonEnabled(Page page, String name, boolean exact) {
	try {
	    Page.GetByRoleOptions opts = new Page.GetByRoleOptions().setName(name);
	    if (exact) {
		opts.setExact(true);
	    }
	    Locator btn = page.getByRole(AriaRole.BUTTON, opts);
	    return btn.count() > 0 && btn.first().isVisible() && btn.first().isEnabled();
	} catch (Exception e) {
	    return false;
	}
    }

    /**
     * True when Accident Date ({@code #accidentDate}) is on screen and empty — form requires it
     * after an accident ICD; blank means we should refresh and move on.
     */
    private boolean isAccidentDateBlank(Page page) {
	try {
	    Locator loc = page.locator(FormSelectors.ACCIDENT_DATE).first();
	    if (loc.count() == 0 || !loc.isVisible()) {
		return false;
	    }
	    String value = loc.inputValue();
	    if (value == null || value.isBlank()) {
		value = loc.getAttribute("value");
	    }
	    return value == null || value.isBlank();
	} catch (Exception e) {
	    logger.warn("Could not read Accident Date: {}", e.getMessage());
	    return false;
	}
    }

    /**
     * After Submit: if coding-edits wizard shows "Override Edit & Submit as Coded",
     * select it then click Confirm so the next chart can load.
     *
     * @param pollAttempts how many 500ms checks to wait for the option (use higher right after Submit)
     * @return true if the override option was found and Confirm was clicked
     */
    private boolean handleOverrideEditAndConfirm(Page page, int pollAttempts) throws InterruptedException {
	try {
	    Locator optionLink = null;
	    int attempts = Math.max(1, pollAttempts);
	    for (int i = 0; i < attempts; i++) {
		Locator label = page.locator("label.ng-binding, label").filter(
			new Locator.FilterOptions().setHasText("Override Edit & Submit as Coded"));
		if (label.count() > 0 && label.first().isVisible()) {
		    optionLink = label.first().locator("xpath=ancestor::a[1]");
		    if (optionLink.count() == 0) {
			optionLink = page.locator("a[ng-click*='selectOption']").filter(
				new Locator.FilterOptions().setHasText("Override Edit & Submit as Coded"));
		    }
		    break;
		}
		Locator byText = page.getByText("Override Edit & Submit as Coded");
		if (byText.count() > 0 && byText.first().isVisible()) {
		    optionLink = byText.first().locator("xpath=ancestor::a[1]");
		    break;
		}
		if (i + 1 < attempts) {
		    Thread.sleep(500);
		}
	    }
	    if (optionLink == null || optionLink.count() == 0) {
		return false;
	    }
	    try {
		if (!optionLink.first().isVisible()) {
		    return false;
		}
	    } catch (Exception e) {
		return false;
	    }

	    logger.info("Coding edit screen — clicking 'Override Edit & Submit as Coded'");
	    try {
		optionLink.first().click(new Locator.ClickOptions().setTimeout(10_000));
	    } catch (Exception e) {
		logger.warn("Normal click on Override option failed ({}) — force click", e.getMessage());
		optionLink.first().click(new Locator.ClickOptions().setForce(true).setTimeout(10_000));
	    }
	    Thread.sleep(1500);

	    Locator confirm = page.locator("button.btn-summary-confirm").first();
	    long deadline = System.currentTimeMillis() + 30_000;
	    while (System.currentTimeMillis() < deadline) {
		try {
		    if (confirm.count() > 0 && confirm.isVisible()) {
			break;
		    }
		} catch (Exception ignored) {
		}
		Locator byRole = page.getByRole(AriaRole.BUTTON,
			new Page.GetByRoleOptions().setName("Confirm"));
		if (byRole.count() > 0 && byRole.first().isVisible()) {
		    confirm = byRole.first();
		    break;
		}
		Thread.sleep(500);
	    }
	    if (confirm.count() == 0 || !confirm.isVisible()) {
		logger.warn("'Override Edit & Submit as Coded' clicked but Confirm not found");
		return false;
	    }
	    logger.info("Clicking Confirm on coding-edits summary");
	    try {
		confirm.click(new Locator.ClickOptions().setTimeout(10_000));
	    } catch (Exception e) {
		confirm.click(new Locator.ClickOptions().setForce(true).setTimeout(10_000));
	    }
	    Thread.sleep(3000);
	    return true;
	} catch (Exception e) {
	    logger.warn("handleOverrideEditAndConfirm: {}", e.getMessage());
	    return false;
	}
    }

    /**
     * Validation Error modal → click OK (#alertok) → reload page so next chart loads.
     *
     * @return true if the modal was found and page was refreshed
     */
    private boolean handleValidationErrorByRefresh(Page page) throws InterruptedException {
	try {
	    Locator title = page.locator("h4.modal-title").filter(
		    new Locator.FilterOptions().setHasText(VALIDATION_ERROR_TITLE));
	    boolean visible = false;
	    try {
		visible = title.count() > 0 && title.first().isVisible();
	    } catch (Exception ignored) {
	    }
	    if (!visible) {
		Locator byText = page.getByText(VALIDATION_ERROR_TITLE);
		visible = byText.count() > 0 && byText.first().isVisible();
	    }
	    if (!visible) {
		return false;
	    }
	    logger.info("Validation Error dialog detected — clicking OK then refreshing page");
	    Locator ok = page.locator("#alertok");
	    if (ok.count() == 0 || !ok.first().isVisible()) {
		ok = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("OK"));
	    }
	    if (ok.count() > 0) {
		ok.first().click(new Locator.ClickOptions().setForce(true));
		Thread.sleep(1000);
	    }
	    page.reload();
	    page.waitForLoadState();
	    Thread.sleep(3000);
	    return true;
	} catch (Exception e) {
	    logger.warn("handleValidationErrorByRefresh: {}", e.getMessage());
	    return false;
	}
    }

    private SkipAdvanceResult refreshPageForNextChart(Page page, String previousFingerprint, String reason)
	    throws InterruptedException {
	logger.info("{} — refreshing Zotec page for next chart", reason);
	try {
	    page.reload();
	    page.waitForLoadState();
	} catch (Exception e) {
	    logger.warn("Page reload failed: {}", e.getMessage());
	}
	Thread.sleep(3000);
	if (hasNoMoreReportsMessage(page)) {
	    return SkipAdvanceResult.NO_MORE_REPORTS;
	}
	return waitForFingerprintAdvance(page, previousFingerprint, "page refresh");
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
