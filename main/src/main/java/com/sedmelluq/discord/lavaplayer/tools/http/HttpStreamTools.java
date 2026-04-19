package com.sedmelluq.discord.lavaplayer.tools.http;

import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;

public class HttpStreamTools {
    public static InputStream streamContent(HttpInterface httpInterface, ClassicHttpRequest request) {
        ClassicHttpResponse response = null;
        boolean success = false;

        try {
            response = httpInterface.execute(request);
            int statusCode = response.getCode();

            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                try {
                    throw new IOException("Invalid status code from " + request.getUri() + " URL: " + statusCode);
                } catch (URISyntaxException e) {
                    throw new IOException("Invalid status code and URI: " + statusCode, e);
                }
            }

            success = true;
            return response.getEntity().getContent();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (response != null && !success) {
                ExceptionTools.closeWithWarnings(response);
            }
        }
    }
}
