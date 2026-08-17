package com.wl.zotecAgent.himer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.*;
import com.wl.util.BrowserCacheClear;
import com.wl.zotecAgent.Flow;

import java.util.List;

@Service
public class BotService {

    private final Flow flow2;

    @Autowired
    public BotService(Flow flow2) {
	this.flow2 = flow2;
    }

    static Thread botThread;
    static volatile boolean running = false;

    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;

    private static volatile boolean shutdownHookRegistered = false;

    // 🔹 START (runs in new thread)
    public void startBot(String agentId) {

	if (running) {
	    System.out.println("⚠ Bot already running");
	    return;
	}

	running = true;
	registerShutdownHook();

	botThread = new Thread(() -> {
	    try {
		System.out.println("🚀 Bot STARTED");

		playwright = Playwright.create();

		BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setChannel("chrome")
			.setHeadless(false).setArgs(List.of("--start-maximized"));

		browser = playwright.chromium().launch(launchOptions);

		context = browser
			.newContext(new Browser.NewContextOptions().setAcceptDownloads(true).setViewportSize(null));
		context.setDefaultNavigationTimeout(60000);
		page = context.newPage();

		// A+B: clear session storage + HTTP cache before login/flow
		BrowserCacheClear.clearSessionAndHttpCache(context);

		flow2.Start(context, agentId);
		Thread.sleep(1000);

	    } catch (Exception e) {
		e.printStackTrace();
	    } finally {
		try {
		    BrowserCacheClear.clearSessionAndHttpCache(context);
		} catch (Exception ignored) {
		}
		System.out.println("🛑 Bot Thread Exited");
	    }
	});

	botThread.start();
    }

    // 🔹 STOP (can be called from another thread)
    static void stopBot() {
	System.out.println("🛑 Bot STOP requested");
	try {
	    BrowserCacheClear.clearSessionAndHttpCache(context);
	    if (page != null)
		page.close();
	    if (context != null)
		context.close();
	    if (browser != null)
		browser.close();
	    if (playwright != null)
		playwright.close();

	    if (running) {
		botThread.interrupt();
		page = null;
		context = null;
		browser = null;
		playwright = null;
	    }
	} catch (Exception e) {
	    // ignore close races
	}

	running = false;

    }

    // 🔹 CLEANUP
    static void cleanup() {
	try {
	    BrowserCacheClear.clearSessionAndHttpCache(context);
	    if (page != null)
		page.close();
	    if (context != null)
		context.close();
	    if (browser != null)
		browser.close();
	    if (playwright != null)
		playwright.close();
	} catch (Exception e) {
	    e.printStackTrace();
	} finally {
	    page = null;
	    context = null;
	    browser = null;
	    playwright = null;
	}
    }

    private static void registerShutdownHook() {
	if (shutdownHookRegistered) {
	    return;
	}
	synchronized (BotService.class) {
	    if (shutdownHookRegistered) {
		return;
	    }
	    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
		try {
		    System.out.println("🛑 JVM shutdown — clearing browser cache (A+B)");
		    BrowserCacheClear.clearSessionAndHttpCache(context);
		} catch (Exception ignored) {
		}
	    }, "himer-bot-browser-cache-clear-shutdown"));
	    shutdownHookRegistered = true;
	}
    }

}
