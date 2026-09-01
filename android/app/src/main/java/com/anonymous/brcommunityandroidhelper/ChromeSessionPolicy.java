package com.anonymous.brcommunityandroidhelper;

import java.net.URI;
import java.util.Locale;

/** Single fail-closed URL policy shared by the native Chrome controller. */
public final class ChromeSessionPolicy {
  private static final int MAX_URL_LENGTH = 4096;
  private ChromeSessionPolicy() {}

  public static String normalizeUrl(String value) {
    if (value == null) return null;
    String input = value.trim();
    if (input.isEmpty() || input.length() > MAX_URL_LENGTH) return null;
    try {
      URI uri = URI.create(input);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null) return null;
      scheme = scheme.toLowerCase(Locale.ROOT);
      host = host.toLowerCase(Locale.ROOT);
      if (!"https".equals(scheme)) return null;
      if (uri.getUserInfo() != null) return null;
      if (uri.getPort() != -1 && uri.getPort() != 443) return null;
      if (!isAllowedHost(host)) return null;
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      StringBuilder out = new StringBuilder("https://").append(host).append(path);
      if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) out.append('?').append(uri.getRawQuery());
      return out.toString();
    } catch (Exception ignored) { return null; }
  }

  private static boolean isAllowedHost(String host) {
    return "youtube.com".equals(host) || "www.youtube.com".equals(host)
      || "m.youtube.com".equals(host) || "music.youtube.com".equals(host)
      || "gaming.youtube.com".equals(host) || "youtu.be".equals(host)
      || "instagram.com".equals(host) || "www.instagram.com".equals(host);
  }

  public static boolean urlMatches(String expected, String observed) {
    String a = normalizeUrl(expected);
    String b = normalizeUrl(observed);
    return a != null && a.equals(b);
  }
}
