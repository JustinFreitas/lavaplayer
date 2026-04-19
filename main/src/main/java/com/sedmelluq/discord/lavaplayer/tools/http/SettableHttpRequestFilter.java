package com.sedmelluq.discord.lavaplayer.tools.http;

import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.client5.http.protocol.HttpClientContext;

public class SettableHttpRequestFilter implements HttpContextFilter {
    private HttpContextFilter filter;

    public HttpContextFilter get() {
        return filter;
    }

    public void set(HttpContextFilter filter) {
        this.filter = filter;
    }

    @Override
    public void onContextOpen(HttpClientContext context) {
        HttpContextFilter current = filter;

        if (current != null) {
            current.onContextOpen(context);
        }
    }

    @Override
    public void onContextClose(HttpClientContext context) {
        HttpContextFilter current = filter;

        if (current != null) {
            current.onContextClose(context);
        }
    }

    @Override
    public void onRequest(HttpClientContext context, ClassicHttpRequest request, boolean isRepetition) {
        HttpContextFilter current = filter;

        if (current != null) {
            current.onRequest(context, request, isRepetition);
        }
    }

    @Override
    public boolean onRequestResponse(HttpClientContext context, ClassicHttpRequest request, HttpResponse response) {
        HttpContextFilter current = filter;

        if (current != null) {
            return current.onRequestResponse(context, request, response);
        } else {
            return false;
        }
    }

    @Override
    public boolean onRequestException(HttpClientContext context, ClassicHttpRequest request, Throwable error) {
        HttpContextFilter current = filter;

        if (current != null) {
            return current.onRequestException(context, request, error);
        } else {
            return false;
        }
    }
}

