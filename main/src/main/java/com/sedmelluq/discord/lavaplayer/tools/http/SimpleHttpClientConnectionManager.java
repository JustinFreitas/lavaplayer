package com.sedmelluq.discord.lavaplayer.tools.http;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.core5.http.io.HttpConnectionFactory;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.Collections;

public class SimpleHttpClientConnectionManager implements HttpClientConnectionManager {
    private static final AtomicLong ID_GENERATOR = new AtomicLong();

    private final HttpClientConnectionOperator connectionOperator;
    private final HttpConnectionFactory<ManagedHttpClientConnection> connectionFactory;
    private volatile SocketConfig socketConfig = SocketConfig.DEFAULT;
    private volatile Http1Config http1Config = Http1Config.DEFAULT;

    public SimpleHttpClientConnectionManager(
        HttpClientConnectionOperator connectionOperator,
        HttpConnectionFactory<ManagedHttpClientConnection> factory
    ) {
        this.connectionOperator = connectionOperator;
        this.connectionFactory = factory;
    }

    public void setSocketConfig(SocketConfig config) {
        this.socketConfig = config;
    }

    public void setHttp1Config(Http1Config config) {
        this.http1Config = config;
    }

    @Override
    public LeaseRequest lease(String id, HttpRoute route, Timeout timeout, Object state) {
        return new LeaseRequest() {
            @Override
            public ConnectionEndpoint get(Timeout timeout) throws InterruptedException, ExecutionException, TimeoutException {
                ManagedHttpClientConnection connection;
                try {
                    if (connectionFactory != null) {
                        connection = connectionFactory.createConnection(null);
                    } else {
                        connection = ManagedHttpClientConnectionFactory.INSTANCE.createConnection(null);
                    }
                } catch (IOException e) {
                    throw new ExecutionException(e);
                }
                return new SimpleConnectionEndpoint(route, connection);
            }

            @Override
            public boolean cancel() {
                return false;
            }
        };
    }

    @Override
    public void release(ConnectionEndpoint endpoint, Object newState, TimeValue validDuration) {
        try {
            endpoint.close();
        } catch (IOException e) {
            // ignore
        }
    }

    @Override
    public void connect(ConnectionEndpoint endpoint, TimeValue connectTimeout, HttpContext context) throws IOException {
        SimpleConnectionEndpoint simpleEndpoint = (SimpleConnectionEndpoint) endpoint;
        HttpRoute route = simpleEndpoint.route;
        HttpHost host;

        if (route.getProxyHost() != null) {
            host = route.getProxyHost();
        } else {
            host = route.getTargetHost();
        }

        InetSocketAddress localAddress = route.getLocalSocketAddress();
        this.connectionOperator.connect(simpleEndpoint.connection, host, localAddress, connectTimeout, this.socketConfig, context);
    }

    @Override
    public void upgrade(ConnectionEndpoint endpoint, HttpContext context) throws IOException {
        SimpleConnectionEndpoint simpleEndpoint = (SimpleConnectionEndpoint) endpoint;
        this.connectionOperator.upgrade(simpleEndpoint.connection, simpleEndpoint.route.getTargetHost(), context);
    }

    public void closeIdle(TimeValue idletime) {
        // Nothing to do.
    }

    public void closeExpired() {
        // Nothing to do.
    }

    public Set<HttpRoute> getRoutes() {
        return Collections.emptySet();
    }

    public void setMaxTotal(int max) {
    }

    public int getMaxTotal() {
        return 0;
    }

    public void setDefaultMaxPerRoute(int max) {
    }

    public int getDefaultMaxPerRoute() {
        return 0;
    }

    public void setMaxPerRoute(HttpRoute route, int max) {
    }

    public int getMaxPerRoute(HttpRoute route) {
        return 0;
    }

    public PoolStats getTotalStats() {
        return new PoolStats(0, 0, 0, 0);
    }

    public PoolStats getStats(HttpRoute route) {
        return new PoolStats(0, 0, 0, 0);
    }

    @Override
    public void close() {
    }

    @Override
    public void close(CloseMode closeMode) {
    }

    private static class SimpleConnectionEndpoint extends ConnectionEndpoint {
        private final HttpRoute route;
        private final ManagedHttpClientConnection connection;

        public SimpleConnectionEndpoint(HttpRoute route, ManagedHttpClientConnection connection) {
            this.route = route;
            this.connection = connection;
        }

        @Override
        public ClassicHttpResponse execute(String id, ClassicHttpRequest request, HttpRequestExecutor requestExecutor, HttpContext context) throws IOException, HttpException {
            return requestExecutor.execute(request, connection, context);
        }

        @Override
        public boolean isConnected() {
            return connection.isOpen();
        }

        @Override
        public void close() throws IOException {
            connection.close();
        }

        @Override
        public void close(CloseMode closeMode) {
            connection.close(closeMode);
        }

        @Override
        public void setSocketTimeout(Timeout timeout) {
            connection.setSocketTimeout(timeout);
        }
    }
}
