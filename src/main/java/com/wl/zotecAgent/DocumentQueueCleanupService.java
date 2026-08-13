package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Document API login + queue cleanup.
 * Auth: static JWT Bearer ({@code bearer.token}) on login and document calls — no session cookie.
 */
@Service
public class DocumentQueueCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQueueCleanupService.class);

    private final RestTemplate restTemplate;

    @Value("${document.review.base-url:http://10.1.240.237:8000}")
    private String baseUrl;

    @Value("${document.auth.login-url:http://10.1.240.245:8000/auth/login}")
    private String authLoginUrl;

    @Value("${document.auth.username:}")
    private String authUsername;

    @Value("${document.auth.password:}")
    private String authPassword;

    @Value("${bot.poll.desktop-id}")
    private String desktopId;

    @Value("${bot.poll.secret}")
    private String botSecret;

    @Value("${bearer.token}")
    private String bearerToken;

    public DocumentQueueCleanupService(RestTemplate restTemplate) {
	this.restTemplate = restTemplate;
    }

    /**
     * {@code POST /auth/login} with the same non-expiring static JWT Bearer.
     * Body: username / password from {@code document.auth.*}.
     */
    @SuppressWarnings("unchecked")
    public void loginForDocumentApi() {
	if (authUsername == null || authUsername.isBlank() || authPassword == null || authPassword.isBlank()) {
	    throw new IllegalStateException(
		    "document.auth.username / document.auth.password must be set for POST /auth/login");
	}
	HttpHeaders headers = DocumentProcessorAuth.loginHeaders(bearerToken);
	Map<String, String> body = new LinkedHashMap<>();
	body.put("username", authUsername.trim());
	body.put("password", authPassword);

	log.info("Document API login: POST {} (username={}, Bearer={})",
		authLoginUrl, authUsername, DocumentProcessorAuth.maskBearer(bearerToken));

	ResponseEntity<Map> response = restTemplate.exchange(
		authLoginUrl,
		HttpMethod.POST,
		new HttpEntity<>(body, headers),
		Map.class);

	Map<String, Object> responseBody = response.getBody();
	log.info("Document API login response status={} body={}",
		response.getStatusCode().value(), responseBody);
	if (responseBody == null) {
	    throw new IllegalStateException("Empty login response from " + authLoginUrl);
	}
    }

    /** Desk + static JWT headers for GET/DELETE /documents. */
    private HttpHeaders documentApiHeaders() {
	return DocumentProcessorAuth.uploadHeaders(desktopId, botSecret, bearerToken);
    }

    /**
     * Lists documents in the queue; deletes each id one-by-one.
     * Skips {@code keepDocumentId} (the record about to be reviewed) if present.
     * If the queue is empty, does nothing — caller continues to review.
     * <p>
     * Call order unchanged: invoked <b>after</b> upload, before review poll.
     */
    @SuppressWarnings("unchecked")
    public void clearQueuedDocumentsExcept(String keepDocumentId) {
	HttpHeaders headers;
	try {
	    headers = documentApiHeaders();
	} catch (Exception e) {
	    log.warn("Document API auth not ready — continuing to review without cleanup: {}", e.getMessage());
	    return;
	}

	String listUrl = baseUrl.replaceAll("/+$", "") + "/documents";
	log.info("Checking document queue: GET {} (Bearer={})",
		listUrl, DocumentProcessorAuth.maskBearer(bearerToken));

	List<String> documentIds = new ArrayList<>();
	try {
	    ResponseEntity<Map> response = restTemplate.exchange(
		    listUrl,
		    HttpMethod.GET,
		    new HttpEntity<>(headers),
		    Map.class);
	    Map<String, Object> body = response.getBody();
	    log.info("GET /documents response: {}", body);

	    if (body != null) {
		Object docsObj = body.get("documents");
		if (docsObj instanceof List<?> docs) {
		    for (Object item : docs) {
			if (!(item instanceof Map<?, ?> m)) {
			    continue;
			}
			Object id = m.get("document_id");
			if (id == null) {
			    id = m.get("documentId");
			}
			if (id == null) {
			    id = m.get("id");
			}
			if (id != null) {
			    String s = String.valueOf(id).trim();
			    if (!s.isEmpty()) {
				documentIds.add(s);
			    }
			}
		    }
		}
	    }
	} catch (Exception e) {
	    log.warn("GET /documents failed — continuing to review without cleanup: {}", e.getMessage());
	    return;
	}

	if (documentIds.isEmpty()) {
	    log.info("No documents in queue — continue to review API");
	    return;
	}

	log.info("Found {} document(s) in queue — deleting one by one: {}", documentIds.size(), documentIds);

	for (String documentId : documentIds) {
	    if (keepDocumentId != null && keepDocumentId.equalsIgnoreCase(documentId)) {
		log.info("Skipping DELETE for current upload document_id={}", documentId);
		continue;
	    }
	    deleteDocument(documentId, headers);
	}
    }

    private void deleteDocument(String documentId, HttpHeaders headers) {
	String deleteUrl = baseUrl.replaceAll("/+$", "") + "/documents/" + documentId;
	try {
	    log.info("DELETE {} (Authorization: Bearer)", deleteUrl);
	    ResponseEntity<String> response = restTemplate.exchange(
		    deleteUrl,
		    HttpMethod.DELETE,
		    new HttpEntity<>(headers),
		    String.class);
	    log.info("Deleted document_id={} status={} body={}",
		    documentId, response.getStatusCode().value(), response.getBody());
	} catch (Exception e) {
	    log.warn("Failed to DELETE document_id={}: {}", documentId, e.getMessage());
	}
    }
}
