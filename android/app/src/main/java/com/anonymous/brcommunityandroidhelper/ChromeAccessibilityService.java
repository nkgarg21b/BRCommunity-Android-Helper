package com.anonymous.brcommunityandroidhelper;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Chrome action gate. Critical timing is owned by NativeChromeScheduler;
 * this service only verifies the currently observable Chrome surface and
 * performs an already-authorized action.
 */
public class ChromeAccessibilityService extends AccessibilityService {
  private static final String CHROME_PACKAGE = "com.android.chrome";
  private static volatile ChromeAccessibilityService instance;

  private static final class SurfaceSnapshot {
    final int windowId; final String url; final String packageName; final String uniqueId;
    SurfaceSnapshot(int windowId, String url, String packageName, String uniqueId) {
      this.windowId = windowId; this.url = url; this.packageName = packageName; this.uniqueId = uniqueId;
    }
  }

  public static ChromeAccessibilityService getInstance() { return instance; }

  @Override public void onServiceConnected() {
    super.onServiceConnected(); instance = this;
    TelemetryLog.event("chrome.service_connected", null, "ok");
    NativeChromeScheduler.get(this).onServiceConnected();
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event == null || event.getPackageName() == null || !CHROME_PACKAGE.contentEquals(event.getPackageName())) return;
    NativeChromeScheduler.get(this).dispatchDue();
  }

  @Override public void onInterrupt() { }

  @Override public void onDestroy() {
    if (instance == this) instance = null;
    TelemetryLog.event("chrome.service_destroyed", null, "ok");
    super.onDestroy();
  }

  public void beginRuntimeSession(String runtimeId) { NativeChromeScheduler.get(this).beginRuntimeSession(runtimeId); }
  public void touchRuntimeSession(String runtimeId) { NativeChromeScheduler.get(this).touchRuntime(runtimeId); }

  public void scheduleTab(String id, String expectedUrl, long closeAt, long engageAt) {
    NativeChromeScheduler scheduler = NativeChromeScheduler.get(this);
    String runtimeId = scheduler.getActiveRuntimeId();
    if (TextUtils.isEmpty(runtimeId)) throw new IllegalStateException("Native Chrome runtime is not active.");
    scheduler.schedule(runtimeId, id, expectedUrl, closeAt, engageAt, false, System.nanoTime());
  }
  public void cancelTab(String id) { NativeChromeScheduler.get(this).cancel(id); }
  public void clearTabs() { NativeChromeScheduler.get(this).clear(); }

  /** Called only by NativeChromeScheduler after token/runtime validation. */
  public void handleNativeAlarm(String id, String reason, long token) {
    NativeChromeScheduler scheduler = NativeChromeScheduler.get(this);
    NativeChromeScheduler.Snapshot entry = scheduler.get(id);
    if (entry == null || entry.token != token || !scheduler.isRuntimeActiveFor(id)) return;
    long now = System.currentTimeMillis();
    if (!entry.engaged && entry.engageAt > 0 && now >= entry.engageAt && now < entry.closeAt) {
      if (engageTabInternal(entry.url)) scheduler.markEngaged(entry.id, entry.token);
    }
    if (now >= entry.closeAt) {
      if (closeTabInternal(entry.url)) scheduler.cancel(entry.id);
      else scheduler.scheduleRetry(entry.id, now + NativeChromeScheduler.RETRY_MS, entry.token);
    }
  }

  public boolean engageTab(String sessionId, String expectedUrl) {
    NativeChromeScheduler scheduler = NativeChromeScheduler.get(this);
    NativeChromeScheduler.Snapshot entry = scheduler.get(sessionId);
    String normalized = ChromeSessionPolicy.normalizeUrl(expectedUrl);
    if (entry == null || normalized == null || !entry.url.equals(normalized) || !scheduler.isRuntimeActiveFor(sessionId)) return false;
    return engageTabInternal(entry.url);
  }

  public boolean closeTab(String sessionId, String expectedUrl) {
    NativeChromeScheduler scheduler = NativeChromeScheduler.get(this);
    NativeChromeScheduler.Snapshot entry = scheduler.get(sessionId);
    String normalized = ChromeSessionPolicy.normalizeUrl(expectedUrl);
    if (entry == null || normalized == null || !entry.url.equals(normalized) || !scheduler.isRuntimeActiveFor(sessionId)) return false;
    return closeTabInternal(entry.url);
  }

  private boolean engageTabInternal(String expectedUrl) {
    SurfaceSnapshot surface = verifySurface(expectedUrl, -1, null);
    if (surface == null) return false;
    AccessibilityNodeInfo root = getRootForWindow(surface.windowId);
    if (root == null) return false;
    boolean clicked = false;
    AccessibilityNodeInfo like = findClickableAction(root, "like", "like this video");
    if (like != null) clicked = like.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clicked;
    AccessibilityNodeInfo subscribe = findClickableAction(root, "subscribe");
    if (subscribe != null) clicked = subscribe.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clicked;
    return clicked;
  }

  private boolean closeTabInternal(String expectedUrl) {
    SurfaceSnapshot surface = verifySurface(expectedUrl, -1, null);
    if (surface == null) return false;
    AccessibilityNodeInfo root = getRootForWindow(surface.windowId);
    if (root == null) return false;
    AccessibilityNodeInfo close = findClickableAction(root, "close tab");
    return close != null && close.performAction(AccessibilityNodeInfo.ACTION_CLICK);
  }

  private SurfaceSnapshot verifySurface(String expectedUrl, int expectedWindowId, String expectedUniqueId) {
    String normalizedExpected = ChromeSessionPolicy.normalizeUrl(expectedUrl);
    if (normalizedExpected == null) return null;
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return null;
    SurfaceSnapshot snapshot = inspectSurface(root);
    if (snapshot == null || !CHROME_PACKAGE.equals(snapshot.packageName)) return null;
    if (!normalizedExpected.equals(snapshot.url)) return null;
    if (expectedWindowId != -1 && snapshot.windowId != expectedWindowId) return null;
    if (!TextUtils.isEmpty(expectedUniqueId) && !expectedUniqueId.equals(snapshot.uniqueId)) return null;
    return snapshot;
  }

  private SurfaceSnapshot inspectSurface(AccessibilityNodeInfo root) {
    String normalized = ChromeSessionPolicy.normalizeUrl(findChromeUrl(root));
    if (normalized == null) return null;
    String unique = Build.VERSION.SDK_INT >= 33 ? safe(root.getUniqueId()) : "";
    return new SurfaceSnapshot(root.getWindowId(), normalized, safe(root.getPackageName()), unique);
  }

  private AccessibilityNodeInfo getRootForWindow(int windowId) {
    if (windowId == AccessibilityWindowInfo.WINDOW_ID_NONE) return null;
    AccessibilityNodeInfo root = getRootInActiveWindow();
    return root != null && root.getWindowId() == windowId ? root : null;
  }

  private String findChromeUrl(AccessibilityNodeInfo root) {
    ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>(); pending.add(root);
    while (!pending.isEmpty()) {
      AccessibilityNodeInfo node = pending.removeFirst();
      String viewId = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
      String text = safe(node.getText()); String desc = safe(node.getContentDescription());
      if (viewId.contains("url_bar") || viewId.contains("location_bar")) {
        if (ChromeSessionPolicy.normalizeUrl(text) != null) return text;
        if (ChromeSessionPolicy.normalizeUrl(desc) != null) return desc;
      }
      if (isLikelyChromeUrlBar(node)) {
        if (ChromeSessionPolicy.normalizeUrl(text) != null) return text;
        if (ChromeSessionPolicy.normalizeUrl(desc) != null) return desc;
      }
      for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) pending.add(child); }
    }
    return "";
  }

  private boolean isLikelyChromeUrlBar(AccessibilityNodeInfo node) {
    String id = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
    if (id.contains("url_bar") || id.contains("location_bar")) return true;
    CharSequence c = node.getClassName();
    return c != null && c.toString().toLowerCase(Locale.ROOT).contains("edittext");
  }

  private AccessibilityNodeInfo findClickableAction(AccessibilityNodeInfo root, String... labels) {
    ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>(); pending.add(root);
    while (!pending.isEmpty()) {
      AccessibilityNodeInfo node = pending.removeFirst();
      String d = safe(node.getContentDescription()).toLowerCase(Locale.ROOT);
      String t = safe(node.getText()).toLowerCase(Locale.ROOT);
      for (String label : labels) {
        String needle = label.toLowerCase(Locale.ROOT);
        if (d.contains(needle) || t.contains(needle)) {
          AccessibilityNodeInfo clickable = node.isClickable() ? node : findClickableAncestor(node);
          if (clickable != null) return clickable;
        }
      }
      for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) pending.add(child); }
    }
    return null;
  }

  private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
    AccessibilityNodeInfo current = node;
    for (int depth = 0; current != null && depth < 5; depth++) { if (current.isClickable()) return current; current = current.getParent(); }
    return null;
  }

  private static String safe(CharSequence value) { return value == null ? "" : value.toString(); }
}
