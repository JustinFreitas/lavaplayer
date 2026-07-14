package com.sedmelluq.discord.lavaplayer.tools.io;

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.http.SettableHttpRequestFilter;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HTTP interface manager which pools interfaces (and their HTTP contexts) for reuse: closing an interface returns it
 * to the pool and a subsequent {@link #getInterface()} may hand it out again. Each interface is used exclusively by
 * one caller at a time. The HTTP client instance used is created lazily. (The class name is historical - the
 * implementation used to keep contexts in a thread local.)
 */
public class ThreadLocalHttpInterfaceManager extends AbstractHttpInterfaceManager {
    private final ConcurrentLinkedQueue<HttpInterface> interfacePool;
    private final SettableHttpRequestFilter filter;

    /**
     * @param clientBuilder HTTP client builder to use for creating the client instance.
     * @param requestConfig Request config used by the client builder
     */
    public ThreadLocalHttpInterfaceManager(HttpClientBuilder clientBuilder, RequestConfig requestConfig) {
        super(clientBuilder, requestConfig);

        this.filter = new SettableHttpRequestFilter();
        this.interfacePool = new ConcurrentLinkedQueue<>();
    }

    @Override
    public HttpInterface getInterface() {
        CloseableHttpClient client = getSharedClient();

        HttpInterface pooled = interfacePool.poll();
        while (pooled != null) {
            // Drop interfaces built for a stale client, or ones that cannot be acquired (which can
            // only happen if a duplicate entry ended up in the pool).
            if (pooled.getHttpClient() == client && pooled.acquire()) {
                return pooled;
            }
            pooled = interfacePool.poll();
        }

        HttpInterface httpInterface = new HttpInterface(client, HttpClientContext.create(), false, filter) {
            @Override
            protected void onClosed() {
                interfacePool.add(this);
            }
        };

        httpInterface.acquire();
        return httpInterface;
    }

    @Override
    public void setHttpContextFilter(HttpContextFilter modifier) {
        filter.set(modifier);
    }
}
