package com.sedmelluq.discord.lavaplayer.tools.http;

import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.client5.http.protocol.HttpClientContext;

public interface HttpContextFilter {
    void onContextOpen(HttpClientContext context);

    void onContextClose(HttpClientContext context);

    void onRequest(HttpClientContext context, ClassicHttpRequest request, boolean isRepetition);

    boolean onRequestResponse(HttpClientContext context, ClassicHttpRequest request, HttpResponse response);

    boolean onRequestException(HttpClientContext context, ClassicHttpRequest request, Throwable error);
}

