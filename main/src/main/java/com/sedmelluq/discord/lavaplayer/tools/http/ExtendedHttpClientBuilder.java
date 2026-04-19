package com.sedmelluq.discord.lavaplayer.tools.http;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.TrustManagerBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.RequestLine;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseParser;
import org.apache.hc.core5.http.impl.io.DefaultClassicHttpResponseFactory;
import org.apache.hc.core5.http.message.BasicLineParser;
import org.apache.hc.core5.http.message.LineParser;
import org.apache.hc.core5.http.message.ParserCursor;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

public class ExtendedHttpClientBuilder extends HttpClientBuilder {
    private static final Logger log = LoggerFactory.getLogger(ExtendedHttpClientBuilder.class);

    private static final SSLContext defaultSslContext = setupSslContext();

    private SSLContext sslContextOverride;
    private String[] sslSupportedProtocols;
    private PlainConnectionSocketFactory plainSocketFactory;
    private SSLConnectionSocketFactory sslSocketFactory;
    private ConnectionManagerFactory connectionManagerFactory = ExtendedHttpClientBuilder::createDefaultConnectionManager;

    @Override
    public CloseableHttpClient build() {
        setConnectionManager(createConnectionManager());
        CloseableHttpClient httpClient = super.build();
        setConnectionManager(null);
        return httpClient;
    }

    public void setSslContextOverride(SSLContext sslContextOverride) {
        this.sslContextOverride = sslContextOverride;
    }

    public void setSslSupportedProtocols(String[] protocols) {
        this.sslSupportedProtocols = protocols;
    }

    public void setPlainConnectionSocketFactory(PlainConnectionSocketFactory plainSocketFactory) {
        this.plainSocketFactory = plainSocketFactory;
    }

    public void setSslConnectionSocketFactory(SSLConnectionSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
    }

    public void setConnectionManagerFactory(ConnectionManagerFactory factory) {
        this.connectionManagerFactory = factory;
    }

    private HttpClientConnectionManager createConnectionManager() {
        return connectionManagerFactory.create(
            new ExtendedConnectionOperator(createConnectionSocketFactory(), null, null),
            createConnectionFactory()
        );
    }

    private Registry<ConnectionSocketFactory> createConnectionSocketFactory() {
        HostnameVerifier hostnameVerifier = new DefaultHostnameVerifier(PublicSuffixMatcherLoader.getDefault());
        ConnectionSocketFactory defaultSslSocketFactory = new SSLConnectionSocketFactory(sslContextOverride != null ?
            sslContextOverride : defaultSslContext, sslSupportedProtocols, null, hostnameVerifier);

        return RegistryBuilder.<ConnectionSocketFactory>create()
            .register("http", plainSocketFactory != null ? plainSocketFactory : PlainConnectionSocketFactory.getSocketFactory())
            .register("https", sslSocketFactory != null ? sslSocketFactory : defaultSslSocketFactory)
            .build();
    }

    private static ManagedHttpClientConnectionFactory createConnectionFactory() {
        return new ManagedHttpClientConnectionFactory(
            null,
            null,
            h1Config -> new GarbageAllergicHttpResponseParser(h1Config, IcyHttpLineParser.ICY_INSTANCE)
        );
    }

    private static HttpClientConnectionManager createDefaultConnectionManager(
        HttpClientConnectionOperator operator,
        ManagedHttpClientConnectionFactory connectionFactory
    ) {
        return new PoolingHttpClientConnectionManager(
            operator,
            PoolConcurrencyPolicy.STRICT,
            PoolReusePolicy.LIFO,
            TimeValue.NEG_ONE_MILLISECOND,
            connectionFactory
        );
    }

    private static SSLContext setupSslContext() {
        try {
            X509TrustManager trustManager = new TrustManagerBuilder()
                .addBuiltinCertificates()
                .addFromResourceDirectory("/certificates")
                .build();

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[]{trustManager}, null);
            return context;
        } catch (Exception e) {
            log.error("Failed to build custom SSL context, using default one.", e);
            return SSLContexts.createDefault();
        }
    }

    private static class GarbageAllergicHttpResponseParser extends DefaultHttpResponseParser {
        private int count = 0;

        public GarbageAllergicHttpResponseParser(
            Http1Config h1Config,
            LineParser lineParser
        ) {
            super(h1Config, lineParser, DefaultClassicHttpResponseFactory.INSTANCE);
        }

        @Override
        protected ClassicHttpResponse createMessage(CharArrayBuffer buffer) throws IOException, HttpException {
            try {
                return super.createMessage(buffer);
            } catch (HttpException e) {
                if (buffer.length() > 4 && "ICY ".equals(buffer.substring(0, 4))) {
                    throw new FriendlyException("ICY protocol is not supported.", COMMON, null);
                } else if (count > 10) {
                    throw new FriendlyException("The server is giving us garbage.", SUSPICIOUS, null);
                }

                count++;
                return null;
            }
        }
    }

    private static class IcyHttpLineParser implements LineParser {
        private static final IcyHttpLineParser ICY_INSTANCE = new IcyHttpLineParser();
        private static final ProtocolVersion ICY_PROTOCOL = new ProtocolVersion("HTTP", 1, 0);

        @Override
        public RequestLine parseRequestLine(CharArrayBuffer buffer) throws ParseException {
            return BasicLineParser.INSTANCE.parseRequestLine(buffer);
        }

        @Override
        public StatusLine parseStatusLine(CharArrayBuffer buffer) throws ParseException {
            if (buffer.length() > 4 && "ICY ".equals(buffer.substring(0, 4))) {
                // ICY [code] [reason]
                return new StatusLine(ICY_PROTOCOL, 200, "OK");
            }

            return BasicLineParser.INSTANCE.parseStatusLine(buffer);
        }

        @Override
        public Header parseHeader(CharArrayBuffer buffer) throws ParseException {
            return BasicLineParser.INSTANCE.parseHeader(buffer);
        }
    }

    public interface ConnectionManagerFactory {
        HttpClientConnectionManager create(
            HttpClientConnectionOperator operator,
            ManagedHttpClientConnectionFactory connectionFactory
        );
    }
}
