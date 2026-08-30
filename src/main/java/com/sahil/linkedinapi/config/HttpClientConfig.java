package com.sahil.linkedinapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;

/**
 * One shared JDK {@link HttpClient} for all outbound LinkedIn traffic.
 *
 * <p>Deliberately the JDK client rather than {@code RestClient}: we need exact control
 * over the cookie and CSRF headers, and — more importantly — we must <em>not</em> follow
 * redirects. A {@code 302} to {@code /authwall} or {@code /checkpoint/challenge} is the
 * single most useful signal LinkedIn gives us that a session has died. Following it
 * silently turns that signal into a 200 with a login page in the body, and the mapper
 * then reports an empty profile instead of a dead session.
 */
@Configuration
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    @Bean
    public HttpClient linkedInHttpClient(AppProperties props) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(props.http().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);

        String proxy = props.http().proxy();
        if (proxy != null && !proxy.isBlank()) {
            URI uri = URI.create(proxy.trim());
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                String user = parts[0];
                char[] pass = (parts.length > 1 ? parts[1] : "").toCharArray();
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                });
            }
            log.info("Outbound LinkedIn traffic routed via proxy {}:{}", uri.getHost(), port);
        }
        return builder.build();
    }
}
