package com.anonymous.brcommunityandroidhelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;

public class ChromeControlModule extends ReactContextBaseJavaModule {
  private static final String CHROME_PACKAGE = "com.android.chrome";
  private static final String CHROME_MAIN_ACTIVITY = "com.google.android.apps.chrome.Main";
  public ChromeControlModule(ReactApplicationContext context) { super(context); }
  @Override public String getName() { return "ChromeControl"; }

  @ReactMethod public void getStatus(Promise promise) { try { WritableMap r=Arguments.createMap(); r.putBoolean("supported",true); r.putBoolean("enabled",isAccessibilityEnabled()); r.putBoolean("chromeInstalled",isChromeInstalled()); promise.resolve(r); } catch(Exception e){promise.reject("CHROME_STATUS_FAILED",e);} }
  @ReactMethod public void openAccessibilitySettings(Promise promise) { try { Intent i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getReactApplicationContext().startActivity(i); promise.resolve(null); } catch(Exception e){promise.reject("ACCESSIBILITY_SETTINGS_FAILED",e);} }

  @ReactMethod public void openChromeUrl(String url,String tabId,boolean incognito,Promise promise){
    try{
      if(!isChromeInstalled()) throw new IllegalStateException("Google Chrome is not installed.");
      if(!isAccessibilityEnabled()) throw new IllegalStateException("Enable Chrome control in Android Accessibility settings first.");
      String normalized=ChromeSessionPolicy.normalizeUrl(url); if(normalized==null) throw new IllegalArgumentException("Unsafe or unsupported URL.");
      Intent intent=new Intent(Intent.ACTION_VIEW, Uri.parse(normalized)); intent.setPackage(CHROME_PACKAGE); intent.setComponent(new ComponentName(CHROME_PACKAGE,CHROME_MAIN_ACTIVITY));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
      intent.putExtra("brcommunity_tab_id",tabId); intent.putExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB",incognito);
      if(intent.resolveActivity(getReactApplicationContext().getPackageManager())==null) throw new IllegalStateException("Google Chrome cannot open this link on this device.");
      getReactApplicationContext().startActivity(intent); promise.resolve(null);
    }catch(Exception e){promise.reject("CHROME_OPEN_FAILED",e);}
  }

  @ReactMethod public void closeTab(String tabId,String expectedUrl,Promise promise){ ChromeAccessibilityService s=ChromeAccessibilityService.getInstance(); if(s==null){promise.reject("CHROME_SERVICE_UNAVAILABLE","Chrome accessibility service is not connected.");return;} promise.resolve(s.closeTab(tabId,expectedUrl)); }
  @ReactMethod public void engageTab(String tabId,String expectedUrl,Promise promise){ ChromeAccessibilityService s=ChromeAccessibilityService.getInstance(); if(s==null){promise.reject("CHROME_SERVICE_UNAVAILABLE","Chrome accessibility service is not connected.");return;} promise.resolve(s.engageTab(tabId,expectedUrl)); }

  @ReactMethod public void beginChromeRuntimeSession(String runtimeId,Promise promise){try{if(ChromeAccessibilityService.getInstance()==null)throw new IllegalStateException("Chrome control service is not connected.");NativeChromeScheduler.get(getReactApplicationContext()).beginRuntimeSession(runtimeId);promise.resolve(null);}catch(Exception e){promise.reject("CHROME_RUNTIME_BEGIN_FAILED",e);}}
  @ReactMethod public void touchChromeRuntimeSession(String runtimeId,Promise promise){try{if(ChromeAccessibilityService.getInstance()==null)throw new IllegalStateException("Chrome control service is not connected.");NativeChromeScheduler.get(getReactApplicationContext()).touchRuntime(runtimeId);promise.resolve(null);}catch(Exception e){promise.reject("CHROME_RUNTIME_TOUCH_FAILED",e);}}
  @ReactMethod public void scheduleTabLifecycle(String tabId,String expectedUrl,double closeAt,double engageAt,Promise promise){try{ChromeAccessibilityService s=ChromeAccessibilityService.getInstance();if(s==null)throw new IllegalStateException("Chrome control service is not connected.");s.scheduleTab(tabId,expectedUrl,(long)closeAt,(long)engageAt);promise.resolve(null);}catch(Exception e){promise.reject("CHROME_SCHEDULE_FAILED",e);}}
  @ReactMethod public void cancelTabLifecycle(String tabId,Promise promise){try{ChromeAccessibilityService s=ChromeAccessibilityService.getInstance();if(s==null)throw new IllegalStateException("Chrome control service is not connected.");s.cancelTab(tabId);promise.resolve(null);}catch(Exception e){promise.reject("CHROME_CANCEL_FAILED",e);}}
  @ReactMethod public void clearTabLifecycles(Promise promise){try{NativeChromeScheduler.get(getReactApplicationContext()).clear();promise.resolve(null);}catch(Exception e){promise.reject("CHROME_CLEAR_SCHEDULE_FAILED",e);}}

  private boolean isChromeInstalled(){try{getReactApplicationContext().getPackageManager().getPackageInfo(CHROME_PACKAGE,0);return true;}catch(PackageManager.NameNotFoundException e){return false;}}
  private boolean isAccessibilityEnabled(){String enabled=Settings.Secure.getString(getReactApplicationContext().getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);if(enabled==null)return false;ComponentName expected=new ComponentName(getReactApplicationContext(),ChromeAccessibilityService.class);for(String entry:enabled.split(":")){ComponentName actual=ComponentName.unflattenFromString(entry);if(expected.equals(actual))return true;}return false;}
}
