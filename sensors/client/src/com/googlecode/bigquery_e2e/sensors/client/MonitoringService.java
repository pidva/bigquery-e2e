package com.googlecode.bigquery_e2e.sensors.client;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MonitoringService extends IntentService {
	public final static String LOG_UPDATE = "com.googlecode.bigquery_e23.sensors.client.log_update";

	private final static String TAG = "MonitoringService";
	private String deviceId = null;
    private JSONObject lastRecord = null;

    public class Binder extends android.os.Binder {
		MonitoringService getService() {
			return MonitoringService.this;
		}
	}

	private final IBinder binder = new Binder(); 
	private PendingIntent pendingIntent;
	
	public MonitoringService() {
		super("Monitoring");
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}
	
	public void start(String deviceId, int intervalMillis) {
		stop();
		this.deviceId = deviceId;
		pendingIntent = PendingIntent.getService(
				this, 0, new Intent(this, MonitoringService.class), 0);
		AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		alarm.setRepeating(AlarmManager.RTC_WAKEUP, Calendar.getInstance().getTimeInMillis(),
				intervalMillis, pendingIntent); 
	}
	
	public void stop() {
		if (pendingIntent != null) {
			AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			alarm.cancel(pendingIntent);
			pendingIntent = null;
		}
		deviceId = null;
	}

	JSONObject getLog() {
		return lastRecord;
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		try {
		  JSONObject newRecord = new JSONObject();
		  newRecord.put("id", deviceId);
		  newRecord.put("ts", ((double) Calendar.getInstance().getTimeInMillis()) / 1000.0);
		  newRecord.put("screen_on",
				  ((PowerManager) getSystemService(Context.POWER_SERVICE)).isScreenOn());
		  newRecord.put("power", getPowerStatus());
		  ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		  newRecord.put("memory", getMemory(activityManager));
		  newRecord.put("running", getRunning(activityManager));
		  lastRecord = newRecord;
		  Intent update = new Intent(LOG_UPDATE);
		  sendBroadcast(update);
		} catch (JSONException ex) {
		  Log.e(TAG, "Failed to build JSON record.", ex);
		}
	}
	
	private JSONObject getPowerStatus() throws JSONException {
		JSONObject power = new JSONObject();
		IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		Intent batteryStatus = registerReceiver(null, ifilter);
		int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		if (status != -1) {
			power.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING);
		}
		int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		if (chargePlug != -1) {
			power.put("usb", chargePlug == BatteryManager.BATTERY_PLUGGED_USB);
			power.put("ac", chargePlug == BatteryManager.BATTERY_PLUGGED_AC);
		}
		int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		if (level != -1 && scale != -1) {
			power.put("charge", level / (double) scale);
		}
		return power;
	}
	
	private JSONObject getMemory(ActivityManager activityManager) throws JSONException {
		MemoryInfo meminfo = new MemoryInfo();
		activityManager.getMemoryInfo(meminfo);
		JSONObject memory = new JSONObject();
		memory.put("available", meminfo.availMem);
		memory.put("low", meminfo.lowMemory);
		memory.put("used", meminfo.totalMem - meminfo.availMem);
		return memory;
	}
	
	private JSONArray getRunning(ActivityManager activityManager) throws JSONException {
		JSONArray running = new JSONArray();
		List<ActivityManager.RunningAppProcessInfo> apps = activityManager.getRunningAppProcesses();
		int[] pids = new int[apps.size()];
		int index = 0;
		for (ActivityManager.RunningAppProcessInfo app : apps) {
			JSONObject entry = new JSONObject();
			entry.put("name", app.processName);
			pids[index++] = app.pid;
			if (app.pid != 0) {
			  entry.put("pid", app.pid);
			}
			entry.put("uid", app.uid);
			if (app.lastTrimLevel > 0) {
			  entry.put("memory_trim", app.lastTrimLevel);
			}
			entry.put("importance", getImportance(app));
			entry.put("package", new JSONArray(Arrays.asList(app.pkgList)));
			running.put(entry);
		}
		Debug.MemoryInfo[] appMemory = activityManager.getProcessMemoryInfo(pids);
		for (int i = 0; i < appMemory.length; i++) {
			if (appMemory[i] != null) {
				running.getJSONObject(i).put("memory", getAppMemory(appMemory[i]));
			}
		}
		return running;
	}
	
	private JSONObject getAppMemory(Debug.MemoryInfo memoryInfo) throws JSONException {
		JSONObject memory = new JSONObject();
		memory.put("total", memoryInfo.getTotalPss());
		memory.put("dirty_private", memoryInfo.getTotalPrivateDirty());
		memory.put("dirty_shared", memoryInfo.getTotalSharedDirty());
		return memory;
	}

	private JSONObject getImportance(ActivityManager.RunningAppProcessInfo app) throws JSONException {
		JSONObject importance = new JSONObject();
		importance.put("level", app.importance);
		importance.put("reason", app.importanceReasonCode);
		importance.put("lru", app.lru);
		if (app.importanceReasonPid != 0) {
		  importance.put("pid", app.importanceReasonPid);
		}
		if (app.importanceReasonComponent != null) {
		  importance.put("component", app.importanceReasonComponent.flattenToString());
		}
		return importance;
	}
}