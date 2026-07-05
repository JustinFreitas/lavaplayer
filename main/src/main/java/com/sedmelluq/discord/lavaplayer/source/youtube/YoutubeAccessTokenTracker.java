package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.*;
import static com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.convertToMapLayout;
import static com.sedmelluq.discord.lavaplayer.tools.ExceptionTools.throwWithDebugInfo;
import static java.nio.charset.StandardCharsets.UTF_8;

public class YoutubeAccessTokenTracker {
    private static final Logger log = LoggerFactory.getLogger(YoutubeAccessTokenTracker.class);

    private static final String AUTH_SCRIPT_REGEX = "<script id=\"base-js\" src=\"(.*?)\" nonce=\".*?\"></script>";
    private static final String IDENTITY_REGEX = "\\{clientId:\"(.+?)\",\\n?.+?:\"(.+?)\"";

    private static final Pattern authScriptPattern = Pattern.compile(AUTH_SCRIPT_REGEX);
    private static final Pattern identityPattern = Pattern.compile(IDENTITY_REGEX);

    private static final String TOKEN_FETCH_CONTEXT_ATTRIBUTE = "yt-raw";
    private static final long MASTER_TOKEN_REFRESH_INTERVAL = TimeUnit.DAYS.toMillis(7);
    private static final long DEFAULT_ACCESS_TOKEN_REFRESH_INTERVAL = TimeUnit.HOURS.toMillis(1);
    private static final long VISITOR_ID_REFRESH_INTERVAL = TimeUnit.MINUTES.toMillis(10);

    private final Object masterTokenLock = new Object();
    private final Object accessTokenLock = new Object();
    private final Object visitorIdLock = new Object();
    
    private final HttpInterfaceManager httpInterfaceManager;
    private final String email;
    private final String password;
    
    private volatile String masterToken;
    private volatile String accessToken;
    private volatile String visitorId;
    private long lastMasterTokenUpdate;
    private long lastAccessTokenUpdate;
    private long lastVisitorIdUpdate;
    private long accessTokenRefreshInterval = DEFAULT_ACCESS_TOKEN_REFRESH_INTERVAL;
    private boolean loggedAgeRestrictionsWarning = false;
    private boolean masterTokenFromTV = false;
    private volatile CachedAuthScript cachedAuthScript = null;

    public YoutubeAccessTokenTracker(HttpInterfaceManager httpInterfaceManager, String email, String password) {
        this.httpInterfaceManager = httpInterfaceManager;
        this.email = email;
        this.password = password;
    }

    /**
     * Updates the master token if more than {@link #MASTER_TOKEN_REFRESH_INTERVAL} time has passed since last updated.
     */
    public void updateMasterToken() {
        synchronized (masterTokenLock) {
            if (DataFormatTools.isNullOrEmpty(email) && DataFormatTools.isNullOrEmpty(password)) {
                if (!loggedAgeRestrictionsWarning) {
                    log.warn("YouTube auth tokens can't be retrieved because email and password is not set in YoutubeAudioSourceManager, age restricted videos will throw exceptions.");
                    loggedAgeRestrictionsWarning = true;
                }
                return;
            }

            if (loggedAgeRestrictionsWarning) return;

            long now = System.currentTimeMillis();
            if (now - lastMasterTokenUpdate < MASTER_TOKEN_REFRESH_INTERVAL) {
                log.debug("YouTube master token was recently updated, not updating again right away.");
                return;
            }

            lastMasterTokenUpdate = now;
            log.info("Updating YouTube master token (current is {}).", masterToken);

            // Don't block main thread since if first auth method failed then we go to second and it's require waiting when user is complete auth.
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100L);
                    String newToken = fetchMasterToken();
                    synchronized (masterTokenLock) {
                        masterToken = newToken;
                    }
                    log.info("Updating YouTube master token succeeded, new token is {}.", masterToken);
                } catch (Exception e) {
                    log.error("YouTube master token update failed.", e);
                }
            });
        }
    }

    /**
     * Updates the access token if more than {@link #accessTokenRefreshInterval} time has passed since last updated.
     */
    public void updateAccessToken() {
        synchronized (accessTokenLock) {
            if (DataFormatTools.isNullOrEmpty(email) && DataFormatTools.isNullOrEmpty(password)) {
                if (!loggedAgeRestrictionsWarning) {
                    log.warn("YouTube auth tokens can't be retrieved because email and password is not set in YoutubeAudioSourceManager, age restricted videos will throw exceptions.");
                    loggedAgeRestrictionsWarning = true;
                }
                return;
            }

            if (DataFormatTools.isNullOrEmpty(masterToken) && loggedAgeRestrictionsWarning) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastAccessTokenUpdate < accessTokenRefreshInterval) {
                log.debug("YouTube access token was recently updated, not updating again right away.");
                return;
            }

            lastAccessTokenUpdate = now;
            log.info("Updating YouTube access token (current is {}).", accessToken);

            try {
                accessToken = fetchAccessToken();
                log.info("Updating YouTube access token succeeded, new token is {}, next update will be after {} seconds.",
                    accessToken,
                    TimeUnit.MILLISECONDS.toSeconds(accessTokenRefreshInterval)
                );
            } catch (Exception e) {
                log.error("YouTube access token update failed.", e);
            }
        }
    }

    /**
     * Updates the visitor id if more than {@link #VISITOR_ID_REFRESH_INTERVAL} time has passed since last updated.
     */
    public String updateVisitorId() {
        synchronized (visitorIdLock) {
            long now = System.currentTimeMillis();
            if (now - lastVisitorIdUpdate < VISITOR_ID_REFRESH_INTERVAL) {
                log.debug("YouTube visitor id was recently updated, not updating again right away.");
                return visitorId;
            }

            lastVisitorIdUpdate = now;
            log.info("Updating YouTube visitor id (current is {}).", visitorId);

            try {
                visitorId = fetchVisitorId();
                log.info("Updating YouTube visitor id succeeded, new one is {}, next update will be after {} seconds.",
                    visitorId,
                    TimeUnit.MILLISECONDS.toSeconds(VISITOR_ID_REFRESH_INTERVAL)
                );
            } catch (Exception e) {
                log.error("YouTube visitor id update failed.", e);
            }

            return visitorId;
        }
    }

    public String getMasterToken() {
        synchronized (masterTokenLock) {
            if (masterToken == null) {
                updateMasterToken();
            }

            return masterToken;
        }
    }

    public String getAccessToken() {
        synchronized (accessTokenLock) {
            if (accessToken == null) {
                updateAccessToken();
            }

            return accessToken;
        }
    }

    public String getVisitorId() {
        synchronized (visitorIdLock) {
            if (visitorId == null) {
                updateVisitorId();
            }

            return visitorId;
        }
    }

    public boolean isTokenFetchContext(HttpClientContext context) {
        return context.getAttribute(TOKEN_FETCH_CONTEXT_ATTRIBUTE) == Boolean.TRUE;
    }

    private String fetchMasterToken() throws IOException {
        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            httpInterface.getContext().setAttribute(TOKEN_FETCH_CONTEXT_ATTRIBUTE, true);

            return requestMasterToken(httpInterface);
        }
    }

    private String fetchAccessToken() throws IOException {
        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            httpInterface.getContext().setAttribute(TOKEN_FETCH_CONTEXT_ATTRIBUTE, true);

            return requestAccessToken(httpInterface);
        }
    }

    private String fetchVisitorId() throws IOException {
        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            httpInterface.getContext().setAttribute(TOKEN_FETCH_CONTEXT_ATTRIBUTE, true);

            YoutubeClientConfig clientConfig = YoutubeClientConfig.ANDROID.copy().setAttribute(httpInterface);
            HttpPost visitorIdPost = new HttpPost(VISITOR_ID_URL);
            StringEntity visitorIdPayload = new org.apache.hc.core5.http.io.entity.StringEntity(clientConfig.toJsonString(), org.apache.hc.core5.http.ContentType.APPLICATION_JSON);
            visitorIdPost.setEntity(visitorIdPayload);

            try (ClassicHttpResponse response = httpInterface.execute(visitorIdPost)) {
                HttpClientTools.assertSuccessWithContent(response, "youtube visitor id");

                String responseText = EntityUtils.toString(response.getEntity());
                JsonBrowser json = JsonBrowser.parse(responseText);

                return json.get("responseContext").get("visitorData").text();
            } catch (ParseException e) {
                throw new IOException(e);
            }
        }
    }

    private String requestMasterToken(HttpInterface httpInterface) throws IOException {
        HttpPost masterTokenPost = new HttpPost(LOGIN_ACCOUNT_URL);
        StringEntity masterTokenPayload = new StringEntity(String.format(TOKEN_PAYLOAD, email, password));
        masterTokenPost.setEntity(masterTokenPayload);

        try (ClassicHttpResponse masterTokenResponse = httpInterface.execute(masterTokenPost)) {
            String responseText = EntityUtils.toString(masterTokenResponse.getEntity(), UTF_8);
            JsonBrowser jsonBrowser = JsonBrowser.parse(responseText);

            if (masterTokenResponse.getCode() == 400) {
                loggedAgeRestrictionsWarning = true;
            }

            HttpClientTools.assertSuccessWithContent(masterTokenResponse, "login account response [" + jsonBrowser.get("exception").safeText() + "]");

            if (jsonBrowser.get("tv").asBoolean(false)) {
                masterTokenFromTV = true;
                return jsonBrowser.get("refresh_token").text();
            } else {
                String services = jsonBrowser.get("services").text();
                if (!jsonBrowser.get("continueUrl").isNull()) {
                    return continueUrl(httpInterface, jsonBrowser);
                } else if (!services.contains("android") || !services.contains("youtube")) {
                    createAndroidAccount(httpInterface, jsonBrowser);
                }
            }

            return jsonBrowser.get("aas_et").text();
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private String requestAccessToken(HttpInterface httpInterface) throws IOException {
        if (masterTokenFromTV) {
            if (cachedAuthScript == null) fetchTVScript(httpInterface);

            HttpPost post = new HttpPost(TV_AUTH_TOKEN_URL);
            post.setEntity(new StringEntity(String.format(TV_AUTH_TOKEN_REFRESH_PAYLOAD,
                cachedAuthScript.clientId,
                cachedAuthScript.clientSecret,
                masterToken
            ), ContentType.APPLICATION_JSON));

            try (ClassicHttpResponse response = httpInterface.execute(post)) {
                HttpClientTools.assertSuccessWithContent(response, "access token tv response");

                String responseText = EntityUtils.toString(response.getEntity(), UTF_8);
                JsonBrowser responseJson = JsonBrowser.parse(responseText);

                accessTokenRefreshInterval = TimeUnit.SECONDS.toMillis(responseJson.get("expires_in").asLong(DEFAULT_ACCESS_TOKEN_REFRESH_INTERVAL));
                return responseJson.get("access_token").text();
            } catch (ParseException e) {
                throw new IOException(e);
            }
        } else {
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("app", "com.google.android.youtube"));
            params.add(new BasicNameValuePair("client_sig", "24bb24c05e47e0aefa68a58a766179d9b613a600"));
            params.add(new BasicNameValuePair("google_play_services_version", "214516005"));
            params.add(new BasicNameValuePair("service", "oauth2:https://www.googleapis.com/auth/youtube"));
            params.add(new BasicNameValuePair("Token", masterToken));
            HttpPost post = new HttpPost(buildUri(ANDROID_AUTH_URL, params));

            try (ClassicHttpResponse response = httpInterface.execute(post)) {
                HttpClientTools.assertSuccessWithContent(response, "access token android response");

                Map<String, String> map = convertToMapLayout(EntityUtils.toString(response.getEntity()));
                accessTokenRefreshInterval = TimeUnit.SECONDS.toMillis(Long.parseLong(map.get("ExpiresInDurationSec")));
                return map.get("Auth");
            } catch (ParseException e) {
                throw new IOException(e);
            }
        }
    }

    private void createAndroidAccount(HttpInterface httpInterface, JsonBrowser jsonBrowser) throws IOException {
        log.info("Account " + jsonBrowser.get("email").text() + " don't have Android or YouTube profile, creating new one...");

        HttpPost post = new HttpPost(CHECKIN_ACCOUNT_URL);
        StringEntity payload = new StringEntity(String.format(TOKEN_PAYLOAD, email, password));
        post.setEntity(payload);

        try (ClassicHttpResponse response = httpInterface.execute(post)) {
            HttpClientTools.assertSuccessWithContent(response, "creating android profile response");
        }
    }

    private String continueUrl(HttpInterface httpInterface, JsonBrowser jsonBrowser) throws IOException {
        log.warn("Not successful attempt to login into account " + jsonBrowser.get("email").text() + ", trying obtain oauth2 token through continue url...");

        HttpPost post = new HttpPost(jsonBrowser.get("continueUrl").text());
        RequestConfig config = RequestConfig.custom().setCookieSpec(StandardCookieSpec.RELAXED).setRedirectsEnabled(true).build();
        post.setConfig(config);

        try (ClassicHttpResponse response = httpInterface.execute(post)) {
            HttpClientTools.assertSuccessWithRedirectContent(response, "oauth2 redirect response");

            URI redirect = httpInterface.getFinalLocation();
            try (ClassicHttpResponse redirectResponse = httpInterface.execute(new HttpGet(redirect))) {
                return exchangeOAuth2Token(httpInterface, redirectResponse);
            }
        }
    }

    private String exchangeOAuth2Token(HttpInterface httpInterface, ClassicHttpResponse response) throws IOException {
        for (Header header : response.getHeaders()) {
            if (header.getName().contains("Set-Cookie") && header.getValue().contains("oauth_token")) {
                String oauthToken = DataFormatTools.extractBetween(header.toString(), "oauth_token=", ";");

                List<NameValuePair> params = new ArrayList<>();
                params.add(new BasicNameValuePair("Token", oauthToken));
                params.add(new BasicNameValuePair("ACCESS_TOKEN", "1"));
                params.add(new BasicNameValuePair("service", "ac2dm"));
                HttpPost post = new HttpPost(buildUri(ANDROID_AUTH_URL, params));

                try (ClassicHttpResponse exchangeResponse = httpInterface.execute(post)) {
                    HttpClientTools.assertSuccessWithContent(exchangeResponse, "exchange oauth2 token response");

                    Map<String, String> map = convertToMapLayout(EntityUtils.toString(exchangeResponse.getEntity()));
                    return map.get("Token");
                } catch (ParseException e) {
                    throw new IOException(e);
                }
            }
        }

        // In case if first auth method failed, start trying second one
        log.warn("First auth method failed, trying second one...");
        return requestAuthCode(httpInterface, fetchTVScript(httpInterface));
    }

    private CachedAuthScript fetchTVScript(HttpInterface httpInterface) throws IOException {
        HttpGet get = new HttpGet(YOUTUBE_ORIGIN + "/tv");
        get.setHeader("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version");

        try (ClassicHttpResponse response = httpInterface.execute(get)) {
            HttpClientTools.assertSuccessWithContent(response, "youtube tv page response");

            String responseText = EntityUtils.toString(response.getEntity());
            Matcher authScript = authScriptPattern.matcher(responseText);

            if (!authScript.find()) {
                throw throwWithDebugInfo(log, null, "no base-js found", "html", responseText);
            }

            return extractIdentity(httpInterface, authScript.group(1));
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private CachedAuthScript extractIdentity(HttpInterface httpInterface, String scriptUrl) throws IOException {
        try (ClassicHttpResponse response = httpInterface.execute(new HttpGet(YOUTUBE_ORIGIN + scriptUrl))) {
            HttpClientTools.assertSuccessWithContent(response, "tv script response");

            String responseText = EntityUtils.toString(response.getEntity());
            Matcher identity = identityPattern.matcher(responseText);

            if (!identity.find()) {
                throw throwWithDebugInfo(log, null, "no identity in base-js found", "js", responseText);
            }

            return cachedAuthScript = new CachedAuthScript(identity.group(1), identity.group(2));
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private String requestAuthCode(HttpInterface httpInterface, CachedAuthScript script) throws IOException {
        HttpPost post = new HttpPost(TV_AUTH_CODE_URL);
        post.setEntity(new StringEntity(String.format(TV_AUTH_CODE_PAYLOAD, script.clientId, UUID.randomUUID()), ContentType.APPLICATION_JSON));

        try (ClassicHttpResponse response = httpInterface.execute(post)) {
            HttpClientTools.assertSuccessWithContent(response, "auth code response");

            String responseText = EntityUtils.toString(response.getEntity(), UTF_8);
            JsonBrowser responseJson = JsonBrowser.parse(responseText);

            return waitForAuth(httpInterface, responseJson, script);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private String waitForAuth(HttpInterface httpInterface, JsonBrowser json, CachedAuthScript script) throws IOException, InterruptedException {
        log.info("Open your browser, go to {} and enter code {}, this is required to complete auth in provided account," +
                " usually this needed to be done once," +
                " LavaPlayer will wait and check for auth completion every 5 seconds.",
            json.get("verification_url").text(),
            json.get("user_code").text()
        );
        Thread.sleep(5000L);

        HttpPost authPost = new HttpPost(TV_AUTH_TOKEN_URL);
        authPost.setEntity(new StringEntity(String.format(TV_AUTH_TOKEN_PAYLOAD,
            script.clientId,
            script.clientSecret,
            json.get("device_code").text()
        ), ContentType.APPLICATION_JSON));

        try (ClassicHttpResponse authResponse = httpInterface.execute(authPost)) {
            HttpClientTools.assertSuccessWithContent(authResponse, "auth wait response");

            String responseText = EntityUtils.toString(authResponse.getEntity(), UTF_8);
            JsonBrowser responseJson = JsonBrowser.parse(responseText);
            JsonBrowser errorJson = responseJson.get("error");

            if (!errorJson.isNull()) {
                String text = errorJson.text();
                if ("authorization_pending".equals(text)) {
                    return waitForAuth(httpInterface, json, script);
                } else if ("expired_token".equals(text)) {
                    log.warn("Token was expired, new one will be generated...");
                    return requestAuthCode(httpInterface, script);
                } else if ("access_denied".equals(text)) {
                    throw new RuntimeException("Auth access was denied, second auth method failed.");
                } else if ("slow_down".equals(text)) {
                    throw new RuntimeException("You are being rate limited, second auth method failed.");
                } else {
                    throw new RuntimeException(String.format("Unknown response from auth (%s)", errorJson.text()));
                }
            } else {
                String refreshToken = responseJson.get("refresh_token").text();
                HttpPost savePost = new HttpPost(SAVE_ACCOUNT_URL);
                savePost.setEntity(new StringEntity(String.format(TOKEN_REFRESH_PAYLOAD,
                    email,
                    password,
                    refreshToken
                ), ContentType.APPLICATION_JSON));

                try (ClassicHttpResponse saveResponse = httpInterface.execute(savePost)) {
                    HttpClientTools.assertSuccessWithContent(saveResponse, "auth save response");

                    accessToken = responseJson.get("access_token").text();
                    accessTokenRefreshInterval = TimeUnit.SECONDS.toMillis(responseJson.get("expires_in").asLong(DEFAULT_ACCESS_TOKEN_REFRESH_INTERVAL));
                    lastAccessTokenUpdate = System.currentTimeMillis();
                    masterTokenFromTV = true;
                    log.info("Auth was successful and updating YouTube access token succeeded, new token is {}, next update will be after {} seconds.",
                        accessToken,
                        TimeUnit.MILLISECONDS.toSeconds(accessTokenRefreshInterval)
                    );
                    return refreshToken;
                }
            }
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private URI buildUri(String url, List<NameValuePair> params) {
        try {
            return new URIBuilder(url)
                .addParameters(params)
                .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    protected static class CachedAuthScript {
        public final String clientId;
        public final String clientSecret;

        public CachedAuthScript(String clientId, String clientSecret) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }
    }
}
