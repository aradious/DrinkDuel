package com.drinkduel.transport;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves safe public Open Graph URLs for an Angular application shell. */
final class OpenGraphMetadata {
    static final String IMAGE_PLACEHOLDER = "__DRINKDUEL_OG_IMAGE__";
    static final String URL_PLACEHOLDER = "__DRINKDUEL_OG_URL__";
    private static final Pattern BASE_HREF = Pattern.compile("<base\\s+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DNS_HOST = Pattern.compile("[A-Za-z0-9.-]+");
    private static final Pattern IPV6_HOST = Pattern.compile("[0-9A-Fa-f:]+");

    private OpenGraphMetadata() {}

    static String render(String html, HttpServletRequest request) {
        String origin = publicOrigin(request);
        String basePath = basePath(html);
        String imageUrl = origin.isEmpty() ? "" : origin + publicPath(basePath, "/images/drinkduel-og.png");
        String pageUrl = origin.isEmpty() ? "" : origin + publicPath(basePath, publicRequestPath(request));
        return html.replace(IMAGE_PLACEHOLDER, htmlAttribute(imageUrl))
                .replace(URL_PLACEHOLDER, htmlAttribute(pageUrl));
    }

    private static String publicOrigin(HttpServletRequest request) {
        String scheme = request.getScheme() == null ? "" : request.getScheme().toLowerCase(Locale.ROOT);
        String host = request.getServerName();
        int port = request.getServerPort();
        if (!("http".equals(scheme) || "https".equals(scheme)) || host == null
                || !(DNS_HOST.matcher(host).matches() || IPV6_HOST.matcher(host).matches())
                || port < 1 || port > 65535) return "";
        int explicitPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)
                ? -1 : port;
        try {
            return new URI(scheme, null, host, explicitPort, null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    private static String basePath(String html) {
        Matcher matcher = BASE_HREF.matcher(html);
        if (!matcher.find()) return "/";
        String value = matcher.group(1);
        if (!value.startsWith("/") || value.contains("..") || value.contains("?") || value.contains("#")) return "/";
        return value.endsWith("/") ? value : value + "/";
    }

    private static String publicRequestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("/index.html".equals(path)) path = "/";
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    private static String publicPath(String basePath, String requestPath) {
        if (!"/".equals(basePath) && requestPath.startsWith(basePath)) return requestPath;
        return basePath + requestPath.replaceFirst("^/+", "");
    }

    private static String htmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
