package com.sedmelluq.lava.extensions.youtuberotator;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

import java.io.IOException;
import java.net.BindException;
import java.net.SocketException;

public class YoutubeIpRotatorRetryHandler implements HttpRequestRetryStrategy {
    @Override
    public boolean retryRequest(HttpRequest request, IOException exception, int executionCount, HttpContext context) {
        if (exception instanceof BindException) {
            return false;
        } else if (exception instanceof SocketException) {
            String message = exception.getMessage();

            if (message != null && message.contains("Protocol family unavailable")) {
                return false;
            }
        }

        return DefaultHttpRequestRetryStrategy.INSTANCE.retryRequest(request, exception, executionCount, context);
    }

    @Override
    public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
        return DefaultHttpRequestRetryStrategy.INSTANCE.retryRequest(response, executionCount, context);
    }

    @Override
    public TimeValue getRetryInterval(HttpResponse response, int executionCount, HttpContext context) {
        return DefaultHttpRequestRetryStrategy.INSTANCE.getRetryInterval(response, executionCount, context);
    }
}
