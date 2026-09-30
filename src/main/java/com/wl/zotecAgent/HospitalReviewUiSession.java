package com.wl.zotecAgent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Hospital review UI at {@code document.review.ui-url}: open once, sign in once with
 * {@code document.auth.*}, then per chart refresh → click Start processing → wait for
 * USER to click Submit review.
 */
@Component
public class HospitalReviewUiSession {

    private static final Logger logger = LogManager.getLogger(HospitalReviewUiSession.class);

    private static final int DEFAULT_TIMEOUT_MS = 180_000;
    /** Wait up to ~60 minutes for the user to click Submit review. */
    private static final int USER_SUBMIT_REVIEW_TIMEOUT_MS = 3_600_000;

    @Value("${document.review.ui-url:http://10.1.240.245:8080/}")
    private String reviewUiUrl;

    @Value("${document.auth.username:}")
    private String authUsername;

    @Value("${document.auth.password:}")
    private String authPassword;

    private Page reviewPage;
    private boolean loggedIn;

    /**
     * Opens the review UI in a new tab once and signs in once. Safe to call every patient.
     */
    public synchronized void ensureOpenAndLoggedIn(BrowserContext context) throws InterruptedException {
	if (context == null) {
	    throw new IllegalArgumentException("BrowserContext is required for review UI");
	}
	if (reviewPage == null || reviewPage.isClosed()) {
	    reviewPage = context.newPage();
	    loggedIn = false;
	    logger.info("Opened hospital review UI tab: {}", reviewUiUrl);
	    reviewPage.navigate(reviewUiUrl);
	    reviewPage.waitForLoadState();
	    Thread.sleep(1500);
	}
	reviewPage.bringToFront();
	if (!loggedIn) {
	    loginIfNeeded();
	    loggedIn = true;
	}
    }

    /**
     * After chart upload: refresh {@code refreshCount} times (1=Text, 2=Image), click
     * Start processing, then wait until the USER clicks Submit review.
     */
    public synchronized void refreshAwaitStartAndSubmitReview(int refreshCount)
	    throws InterruptedException {
	if (reviewPage == null || reviewPage.isClosed()) {
	    throw new IllegalStateException("Review UI tab is not open — call ensureOpenAndLoggedIn first");
	}
	reviewPage.bringToFront();

	int times = Math.max(1, refreshCount);
	for (int i = 1; i <= times; i++) {
	    logger.info("Refreshing hospital review UI ({}/{})", i, times);
	    reviewPage.reload();
	    reviewPage.waitForLoadState();
	    Thread.sleep(1500);
	}

	Locator startProcessing = waitForButtonVisible("Start processing", DEFAULT_TIMEOUT_MS);
	logger.info("Start processing button is visible — clicking");
	clickWhenEnabled(startProcessing, "Start processing");
	Thread.sleep(1500);

	waitForButtonVisible("Submit review", DEFAULT_TIMEOUT_MS);
	logger.info(
		"Submit review is visible — waiting for USER to click it (bot will not click Submit review)");
	waitUntilButtonGone("Submit review", USER_SUBMIT_REVIEW_TIMEOUT_MS);
	logger.info("Submit review no longer visible — assuming user completed review");
	Thread.sleep(1500);
    }

    public Page getReviewPage() {
	return reviewPage;
    }

    private void loginIfNeeded() throws InterruptedException {
	if (authUsername == null || authUsername.isBlank()
		|| authPassword == null || authPassword.isBlank()) {
	    throw new IllegalStateException(
		    "document.auth.username / document.auth.password must be set for review UI login");
	}

	Locator username = reviewPage.locator("input[autocomplete='username']").first();
	Locator password = reviewPage.locator("input[autocomplete='current-password']").first();
	boolean signInFormVisible = false;
	try {
	    username.waitFor(new Locator.WaitForOptions()
		    .setState(WaitForSelectorState.VISIBLE)
		    .setTimeout(8_000));
	    signInFormVisible = username.isVisible();
	} catch (Exception e) {
	    logger.info("No Sign in form visible — assuming already authenticated on review UI");
	}
	if (!signInFormVisible) {
	    return;
	}

	logger.info("Signing into hospital review UI as {}", authUsername);
	username.fill(authUsername);
	password.fill(authPassword);
	Thread.sleep(300);

	Locator signIn = reviewPage.getByRole(AriaRole.BUTTON,
		new Page.GetByRoleOptions().setName("Sign in"));
	try {
	    signIn.first().waitFor(new Locator.WaitForOptions()
		    .setState(WaitForSelectorState.VISIBLE)
		    .setTimeout(10_000));
	    clickWhenEnabled(signIn.first(), "Sign in");
	} catch (Exception e) {
	    logger.warn("Sign in click via role failed ({}) — submitting form", e.getMessage());
	    reviewPage.locator("form").first().evaluate("form => form.requestSubmit()");
	}
	reviewPage.waitForLoadState();
	Thread.sleep(2000);
	logger.info("Hospital review UI sign-in completed");
    }

    private Locator waitForButtonVisible(String name, int timeoutMs) throws InterruptedException {
	Locator btn = reviewPage.getByRole(AriaRole.BUTTON,
		new Page.GetByRoleOptions().setName(name));
	long deadline = System.currentTimeMillis() + timeoutMs;
	while (System.currentTimeMillis() < deadline) {
	    try {
		if (btn.count() > 0 && btn.first().isVisible()) {
		    return btn.first();
		}
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(1000);
	}
	throw new IllegalStateException(
		"Timed out waiting for button '" + name + "' on hospital review UI");
    }

    /** Wait until a previously visible button is gone (user clicked / UI advanced). */
    private void waitUntilButtonGone(String name, int timeoutMs) throws InterruptedException {
	Locator btn = reviewPage.getByRole(AriaRole.BUTTON,
		new Page.GetByRoleOptions().setName(name));
	long deadline = System.currentTimeMillis() + timeoutMs;
	int loggedEvery = 0;
	while (System.currentTimeMillis() < deadline) {
	    try {
		boolean visible = btn.count() > 0 && btn.first().isVisible();
		if (!visible) {
		    return;
		}
	    } catch (Exception e) {
		// detached / navigated — treat as gone
		return;
	    }
	    loggedEvery++;
	    if (loggedEvery % 30 == 0) {
		logger.info("Still waiting for USER to click '{}'... ({}s)", name, loggedEvery);
	    }
	    Thread.sleep(1000);
	}
	throw new IllegalStateException(
		"Timed out waiting for USER to click '" + name + "' on hospital review UI");
    }

    private void clickWhenEnabled(Locator button, String label) throws InterruptedException {
	for (int i = 0; i < 30; i++) {
	    try {
		if (button.isEnabled()) {
		    break;
		}
	    } catch (Exception ignored) {
	    }
	    Thread.sleep(200);
	}
	button.click(new Locator.ClickOptions().setForce(true).setTimeout(30_000));
	logger.info("Clicked '{}'", label);
    }
}
