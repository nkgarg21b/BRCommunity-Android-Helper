package com.anonymous.brcommunityandroidhelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Durable native timing authority for Chrome session lifecycle actions. */
public final class NativeChromeScheduler {
  private static final String PREFS = "brcommunity_native_scheduler";
  private static final String PREF_RUNTIME = "runtime_id";
  private static final String PREF_LEASE = "runtime_lease_until";
  private static final String PREF_SCHEDULES = "schedules";
  private static final long LEASE_MS = 120_000L;
  private static final long MIN_DELAY_MS = 250L;
  public static final long RETRY_MS = 1200L;
  private static volatile NativeChromeScheduler singleton;
  private final Context context;
  private final AlarmManager alarms;
  private final Map<String, Entry> entries = new HashMap<>();

  private static final class Entry {
    String runtimeId, id, url; long closeAt, engageAt, token; boolean engaged;
  }

  public static final class Snapshot {
    public final String runtimeId, id, url; public final long closeAt, engageAt, token; public final boolean engaged;
    Snapshot(Entry e) { runtimeId=e.runtimeId; id=e.id; url=e.url; closeAt=e.closeAt; engageAt=e.engageAt; token=e.token; engaged=e.engaged; }
  }

  private NativeChromeScheduler(Context context) { this.context=context.getApplicationContext(); this.alarms=(AlarmManager)this.context.getSystemService(Context.ALARM_SERVICE); load(); }
  public static NativeChromeScheduler get(Context context) { if (singleton==null) synchronized(NativeChromeScheduler.class){ if(singleton==null) singleton=new NativeChromeScheduler(context); } return singleton; }

  public synchronized void onServiceConnected() {
    load();
    if (TextUtils.isEmpty(getActiveRuntimeId())) { entries.clear(); cancelAllAlarms(); save(); return; }
    dispatchDue(); armAll();
  }

  public synchronized void beginRuntimeSession(String runtimeId) {
    if (TextUtils.isEmpty(runtimeId)) throw new IllegalArgumentException("runtimeId is required");
    cancelAllAlarms(); entries.clear();
    prefs().edit().putString(PREF_RUNTIME,runtimeId).putLong(PREF_LEASE,System.currentTimeMillis()+LEASE_MS).putString(PREF_SCHEDULES,"[]").apply();
  }

  public synchronized void touchRuntime(String runtimeId) {
    if (!isRuntimeIdActive(runtimeId)) return;
    prefs().edit().putLong(PREF_LEASE,System.currentTimeMillis()+LEASE_MS).apply(); armAll();
  }

  public synchronized String getActiveRuntimeId() {
    String id=prefs().getString(PREF_RUNTIME,""); long lease=prefs().getLong(PREF_LEASE,0L);
    return lease>System.currentTimeMillis()?id:"";
  }

  private boolean isRuntimeIdActive(String runtimeId) { String active=getActiveRuntimeId(); return !TextUtils.isEmpty(runtimeId)&&runtimeId.equals(active); }
  private boolean isRuntime(String runtimeId) { return isRuntimeIdActive(runtimeId); }
  public synchronized boolean isRuntimeActiveFor(String id) { Entry e=entries.get(id); return e!=null&&isRuntime(e.runtimeId); }
  public synchronized Snapshot get(String id) { Entry e=entries.get(id); return e==null?null:new Snapshot(e); }

  public synchronized void schedule(String runtimeId,String id,String url,long closeAt,long engageAt,boolean engaged,long token) {
    String normalized=ChromeSessionPolicy.normalizeUrl(url);
    if (!isRuntime(runtimeId)) throw new IllegalStateException("Native runtime lease is inactive.");
    if (TextUtils.isEmpty(id)||normalized==null||closeAt<=System.currentTimeMillis()||token==0L) throw new IllegalArgumentException("Invalid Chrome schedule.");
    cancelAlarm(id,"engage"); cancelAlarm(id,"close"); cancelAlarm(id,"retry");
    Entry e=new Entry(); e.runtimeId=runtimeId; e.id=id; e.url=normalized; e.closeAt=closeAt; e.engageAt=engageAt; e.engaged=engaged; e.token=token;
    entries.put(id,e); save(); arm(e);
  }

  public synchronized void scheduleRetry(String id,long at,long token) {
    Entry e=entries.get(id); if(e==null||e.token!=token||!isRuntime(e.runtimeId)) return;
    PendingIntent pi=pending(intent(id,"retry",token),PendingIntent.FLAG_UPDATE_CURRENT);
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(System.currentTimeMillis()+MIN_DELAY_MS,at),pi);
  }

  public synchronized void markEngaged(String id,long token) {
    Entry e=entries.get(id); if(e==null||e.token!=token||!isRuntime(e.runtimeId)) return;
    e.engaged=true; cancelAlarm(id,"engage"); save(); arm(e);
  }

  public synchronized void cancel(String id) { entries.remove(id); cancelAlarm(id,"engage"); cancelAlarm(id,"close"); cancelAlarm(id,"retry"); save(); }
  public synchronized void clear() { entries.clear(); cancelAllAlarms(); save(); }

  public synchronized void dispatchDue() {
    if(TextUtils.isEmpty(getActiveRuntimeId())) return;
    ChromeAccessibilityService service=ChromeAccessibilityService.getInstance(); if(service==null) return;
    long now=System.currentTimeMillis();
    for(Entry e:entries.values().toArray(new Entry[0])) if(isRuntime(e.runtimeId)&&(now>=e.closeAt||(!e.engaged&&e.engageAt>0&&now>=e.engageAt))) service.handleNativeAlarm(e.id,"due",e.token);
  }

  public synchronized void onAlarm(String id,long token) {
    Entry e=entries.get(id); if(e==null||e.token!=token||!isRuntime(e.runtimeId)) return;
    ChromeAccessibilityService service=ChromeAccessibilityService.getInstance(); if(service!=null) service.handleNativeAlarm(id,"alarm",token); else arm(e);
  }

  private void armAll(){ for(Entry e:entries.values()) arm(e); }
  private void arm(Entry e){ if(!isRuntime(e.runtimeId)) return; long now=System.currentTimeMillis();
    if(!e.engaged&&e.engageAt>0&&e.engageAt<e.closeAt) setAlarm(e.id,"engage",Math.max(now+MIN_DELAY_MS,e.engageAt),e.token);
    if(e.closeAt>now) setAlarm(e.id,"close",e.closeAt,e.token);
  }
  private void setAlarm(String id,String action,long at,long token){ alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(System.currentTimeMillis()+MIN_DELAY_MS,at),pending(intent(id,action,token),PendingIntent.FLAG_UPDATE_CURRENT)); }
  private Intent intent(String id,String action,long token){ Intent i=new Intent(context,ChromeScheduleReceiver.class); i.putExtra(ChromeScheduleReceiver.EXTRA_ID,id); i.putExtra(ChromeScheduleReceiver.EXTRA_ACTION,action); i.putExtra(ChromeScheduleReceiver.EXTRA_TOKEN,token); i.setData(Uri.parse("brcommunity://chrome-schedule/"+Uri.encode(id)+"/"+Uri.encode(action))); return i; }
  private PendingIntent pending(Intent i,int flags){ int f=flags; if(android.os.Build.VERSION.SDK_INT>=23) f|=PendingIntent.FLAG_IMMUTABLE; return PendingIntent.getBroadcast(context,0,i,f); }
  private void cancelAlarm(String id,String action){ PendingIntent pi=pending(intent(id,action,0L),PendingIntent.FLAG_NO_CREATE); if(pi!=null){alarms.cancel(pi);pi.cancel();} }
  private void cancelAllAlarms(){ for(Entry e:entries.values()){cancelAlarm(e.id,"engage");cancelAlarm(e.id,"close");cancelAlarm(e.id,"retry");} }
  private android.content.SharedPreferences prefs(){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
  private synchronized void save(){ JSONArray a=new JSONArray(); try{for(Entry e:entries.values()){JSONObject o=new JSONObject();o.put("runtimeId",e.runtimeId);o.put("id",e.id);o.put("url",e.url);o.put("closeAt",e.closeAt);o.put("engageAt",e.engageAt);o.put("engaged",e.engaged);o.put("token",e.token);a.put(o);}}catch(Exception ignored){} prefs().edit().putString(PREF_SCHEDULES,a.toString()).apply(); }
  private synchronized void load(){ entries.clear(); String raw=prefs().getString(PREF_SCHEDULES,"[]"); try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;Entry e=new Entry();e.runtimeId=o.optString("runtimeId","");e.id=o.optString("id","");e.url=ChromeSessionPolicy.normalizeUrl(o.optString("url",""));e.closeAt=o.optLong("closeAt",0L);e.engageAt=o.optLong("engageAt",0L);e.engaged=o.optBoolean("engaged",false);e.token=o.optLong("token",0L);if(!TextUtils.isEmpty(e.runtimeId)&&!TextUtils.isEmpty(e.id)&&e.url!=null&&e.closeAt>0&&e.token!=0L)entries.put(e.id,e);}}catch(Exception ignored){prefs().edit().putString(PREF_SCHEDULES,"[]").apply();} }
}
