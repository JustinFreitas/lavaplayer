package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class DefaultSoundCloudDataLoader implements SoundCloudDataLoader {
    @Override
    public JsonBrowser load(HttpInterface httpInterface, String url) throws IOException {
        try (ClassicHttpResponse response = httpInterface.execute(new HttpGet(buildUri(url)))) {
            if (response.getCode() == HttpStatus.SC_NOT_FOUND) {
                return JsonBrowser.NULL_BROWSER;
            }

            HttpClientTools.assertSuccessWithContent(response, "video page response");

            String json = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            return JsonBrowser.parse(json);
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    private URI buildUri(String url) {
        try {
            return new URIBuilder("https://api-v2.soundcloud.com/resolve")
                .addParameter("url", url)
                .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
