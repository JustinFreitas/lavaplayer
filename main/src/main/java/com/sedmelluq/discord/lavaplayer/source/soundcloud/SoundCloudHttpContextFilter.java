package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextRetryCounter;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.net.URISyntaxException;

public class SoundCloudHttpContextFilter implements HttpContextFilter {
    private static final HttpContextRetryCounter retryCounter = new HttpContextRetryCounter("sc-id-retry");

    private final SoundCloudClientIdTracker clientIdTracker;

    public SoundCloudHttpContextFilter(SoundCloudClientIdTracker clientIdTracker) {
        this.clientIdTracker = clientIdTracker;
    }

    @Override
    public void onContextOpen(HttpClientContext context) {

    }

    @Override
    public void onContextClose(HttpClientContext context) {

    }

    @Override
    public void onRequest(HttpClientContext context, ClassicHttpRequest request, boolean isRepetition) {
        request.setHeader("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/76.0.3809.100 Safari/537.36");

        retryCounter.handleUpdate(context, isRepetition);

        if (clientIdTracker.isIdFetchContext(context)) {
            // Used for fetching client ID, let's not recurse.
            return;
        }

        try {
            if (request.getUri().getHost().contains("sndcdn.com")) {
                // CDN urls do not require client ID (it actually breaks them)
                return;
            }

            URI uri = new URIBuilder(request.getUri())
                .setParameter("client_id", clientIdTracker.getClientId())
                .build();

            request.setUri(uri);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onRequestResponse(HttpClientContext context, ClassicHttpRequest request, HttpResponse response) {
        if (clientIdTracker.isIdFetchContext(context) || retryCounter.getRetryCount(context) >= 1) {
            return false;
        } else if (response.getCode() == HttpStatus.SC_UNAUTHORIZED) {
            clientIdTracker.updateClientId();
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean onRequestException(HttpClientContext context, ClassicHttpRequest request, Throwable error) {
        return false;
    }
}
