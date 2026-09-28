package com.wl.zotecAgent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * ZTEC logger verifier overlay: {@code <button class="wl_zt_verifier_action">Yes, I did</button>}
 * sits over Submit / Skip / RFI / Move to Issue until dismissed.
 */
public final class ZtecVerifierGate {

    private static final Logger log = LogManager.getLogger(ZtecVerifierGate.class);

    /** Prefer the ZTEC class; also match by visible label. */
    private static final String CLASS_SELECTOR = "button.wl_zt_verifier_action";

    private ZtecVerifierGate() {
    }

    /**
     * If "Yes, I did" is visible, click it so Submit / Skip / RFI / Move to Issue can be used.
     *
     * @return {@code true} if the button was found and clicked
     */
    public static boolean dismissYesIDidIfPresent(Page page) {
	if (page == null) {
	    return false;
	}
	try {
	    Locator btn = findYesIDid(page);
	    if (btn == null) {
		return false;
	    }
	    log.info("ZTEC 'Yes, I did' visible — clicking so Submit/Skip/RFI/Move to Issue are usable");
	    clickYesIDid(btn);
	    return true;
	} catch (Exception e) {
	    log.warn("Could not dismiss ZTEC 'Yes, I did': {}", e.getMessage());
	    return false;
	}
    }

    /**
     * Before Submit/Skip: wait briefly for {@code Yes, I did}, click it when it appears
     * (retry a few times), then wait until Submit or Skip is visible/enabled.
     */
    public static void dismissYesIDidThenRevealSubmitSkip(Page page) throws InterruptedException {
	if (page == null) {
	    return;
	}
	boolean clicked = false;
	for (int attempt = 1; attempt <= 15; attempt++) {
	    Locator btn = findYesIDid(page);
	    if (btn != null) {
		log.info("ZTEC 'Yes, I did' found (attempt {}) — clicking before Submit/Skip", attempt);
		clickYesIDid(btn);
		clicked = true;
		Thread.sleep(800);
		// Overlay may reappear; keep clearing until gone or Submit/Skip usable
		if (findYesIDid(page) == null) {
		    break;
		}
		continue;
	    }
	    if (clicked || submitOrSkipUsable(page)) {
		break;
	    }
	    Thread.sleep(500);
	}

	// After dismiss, wait for Submit or Skip to show
	for (int i = 0; i < 20; i++) {
	    if (findYesIDid(page) != null) {
		dismissYesIDidIfPresent(page);
		Thread.sleep(500);
		continue;
	    }
	    if (submitOrSkipUsable(page)) {
		log.info("Submit/Skip visible after ZTEC 'Yes, I did' dismiss");
		return;
	    }
	    Thread.sleep(500);
	}
	log.warn("Submit/Skip not clearly visible after ZTEC dismiss — will still try to click");
    }

    private static Locator findYesIDid(Page page) {
	try {
	    Locator btn = page.locator(CLASS_SELECTOR).first();
	    if (btn.count() > 0 && btn.isVisible()) {
		return btn;
	    }
	} catch (Exception ignored) {
	}
	try {
	    Locator btn = page.getByRole(AriaRole.BUTTON,
		    new Page.GetByRoleOptions().setName("Yes, I did")).first();
	    if (btn.count() > 0 && btn.isVisible()) {
		return btn;
	    }
	} catch (Exception ignored) {
	}
	return null;
    }

    private static void clickYesIDid(Locator btn) {
	try {
	    btn.scrollIntoViewIfNeeded();
	} catch (Exception ignored) {
	}
	try {
	    btn.click(new Locator.ClickOptions().setTimeout(5_000));
	} catch (Exception e) {
	    log.warn("Normal click on 'Yes, I did' failed ({}) — force click", e.getMessage());
	    btn.click(new Locator.ClickOptions().setForce(true).setTimeout(5_000));
	}
	try {
	    Thread.sleep(500);
	} catch (InterruptedException ie) {
	    Thread.currentThread().interrupt();
	}
    }

    private static boolean submitOrSkipUsable(Page page) {
	try {
	    Locator submit = page.getByRole(AriaRole.BUTTON,
		    new Page.GetByRoleOptions().setName("Submit").setExact(true)).first();
	    if (submit.count() > 0 && submit.isVisible()) {
		return true;
	    }
	} catch (Exception ignored) {
	}
	try {
	    Locator skip = page.getByRole(AriaRole.BUTTON,
		    new Page.GetByRoleOptions().setName("Skip")).first();
	    if (skip.count() > 0 && skip.isVisible()) {
		return true;
	    }
	} catch (Exception ignored) {
	}
	return false;
    }
}
