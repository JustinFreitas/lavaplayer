package com.sedmelluq.discord.lavaplayer.tools.io;

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.http.SettableHttpRequestFilter;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HTTP interface manager which reuses an HttpContext by keeping it as a thread local. In case a new interface is
 * requested before the previous one has been closed, it creates a new context for the returned interface. The HTTP
 * client instance used is created lazily.
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

        HttpInterface httpInterface = interfacePool.poll();
        while (httpInterface != null) {
            if (httpInterface.getHttpClient() == client) {
                break;
            }
            httpInterface = interfacePool.poll();
        }

        if (httpInterface == null) {
            httpInterface = new HttpInterface(client, HttpClientContext.create(), false, filter) {
                @Override
                public void close() throws IOException {
                    super.close();
                    interfacePool.add(this);
                }
            };
        }

        httpInterface.acquire();
        return httpInterface;
    }

    @Override
    public void setHttpContextFilter(HttpContextFilter modifier) {
        filter.set(modifier);
    }
}
