package com.sedmelluq.discord.lavaplayer.tools.io;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents a class where HTTP request configuration can be changed.
 */
public interface HttpConfigurable {
    /**
     * @param configurator Function to reconfigure request config.
     */
    void configureRequests(Function<RequestConfig, RequestConfig> configurator);

    /**
     * @param configurator Function to reconfigure HTTP builder.
     */
    void configureBuilder(Consumer<HttpClientBuilder> configurator);
}
