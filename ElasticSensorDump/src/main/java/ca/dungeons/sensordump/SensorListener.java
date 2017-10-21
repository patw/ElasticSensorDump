package ca.dungeons.sensordump;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Created by Gurtok on 8/14/2017. */
class SensorListener extends Thread implements android.hardware.SensorEventListener {

  /** Use this to identify this classes log messages. */
  private final String logTag = "SensorListener";

  private EsdServiceManager serviceManager;

  /** Main activity context. */
  private final Context passedContext;

  /** Applications' shared preferences. */
  private final SharedPreferences sharedPrefs;

  /** Gives access to the local database via a helper class. */
  private DatabaseHelper dbHelper;
  /** */
  private final ExecutorService threadPool = Executors.newSingleThreadExecutor();

// Date / Time variables.
  /** A static reference to the custom date format. */
  @SuppressWarnings("SpellCheckingInspection")
  private final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ", Locale.US);

  /** Timers, the schema is defined else where. */
  private final long startTime;
  /** Used to get access to GPS. */
  private final LocationManager locationManager;

// Sensor variables.
  private long lastUpdate;
  /** If we are currently logging PHONE sensor data. */
  private boolean sensorLogging = false;
  /** Instance of sensorMessageHandler Manager. */
  private SensorManager mSensorManager;
  /** Each loop, data wrapper to upload to Elastic. */
  private JSONObject joSensorData = new JSONObject();
  /** Array to hold sensorMessageHandler references. */
  private List<Integer> usableSensorList;
  /** Refresh time in milliseconds. Default = 250ms. */
  private int sensorRefreshTime = 250;
  /** If listeners are active. */
  private boolean sensorsRegistered = false;
  /** Listener for battery updates. */
  private BroadcastReceiver batteryReceiver;

// GPS variables.
  /** Battery level in percentages. */
  private double batteryLevel = 0;
  /** Helper class to organize gps data. */
  private GPSLogger gpsLogger = new GPSLogger();

  /** Control for telling if we have already registered the gps listeners. */
  private boolean gpsRegistered;

// AUDIO variables.
  /** Helper class for obtaining audio data. */
  private AudioRunnable audioRunnable;

  /** Control variable to make sure we only create one audio logger. */
  private boolean audioRegistered;

  /** Default Constructor. */
  SensorListener(Context context, SharedPreferences sharedPreferences, DatabaseHelper dbHelper, EsdServiceManager serviceManger) {
    sharedPrefs = sharedPreferences;
    passedContext = context;
    this.serviceManager = serviceManger;
    this.dbHelper = dbHelper;
    locationManager = (LocationManager) passedContext.getSystemService(Context.LOCATION_SERVICE);
    gpsLogger = new GPSLogger();
    audioRunnable = new AudioRunnable();
    startTime = lastUpdate = System.currentTimeMillis();
    parseSensorArray();

  }

  @Override
  public synchronized void start() {
    super.start();
  }

  @Override
  public void run() {
    super.run();
  }

  @Override
  public void interrupt() {
    super.interrupt();
  }

  /**
   * This is the main recording loop. One reading per sensorMessageHandler per loop.
   * Update timestamp in sensorMessageHandler data structure.
   * Store the logging start time with each document.
   * Store the duration of the sensorMessageHandler log with each document.
   * Dump gps data into document if it's ready.
   * Put battery status percentage into the Json.
   * @param event A reference to the event object.
   */
  @Override
  public final void onSensorChanged(SensorEvent event) {
    // Check if we should be shutting down sensor recording.
    if ( isInterrupted() ) {
      unregisterSensorListeners();
      return;
    }

    if (System.currentTimeMillis() > lastUpdate + sensorRefreshTime && sensorsRegistered) {
      // ^^ Make sure we generate docs at an adjustable rate.
      // 250ms is the default setting.

      // Reset our flags to update the service manager about the type of sensor readings.
      boolean gpsReading = false;
      boolean audioReading = false;

      String sensorName;
      String[] sensorHierarchyName;
      try {
        joSensorData.put("@timestamp", logDateFormat.format(new Date(System.currentTimeMillis())));
        joSensorData.put("start_time", logDateFormat.format(new Date(startTime)));
        joSensorData.put("log_duration_seconds", (System.currentTimeMillis() - startTime) / 1000);

        //Log.e(logTag, "gpsRegistered: " + gpsRegistered + " gps has data? " + gpsLogger.gpsHasData );
        if (gpsRegistered && gpsLogger.gpsHasData) {
          joSensorData = gpsLogger.getGpsData(joSensorData);
          gpsReading = true;
        }

        //Log.e(logTag, "audioRegistered: " + audioRegistered + " gps has data? " + gpsLogger.gpsHasData );
        if (audioRegistered && audioRunnable.hasData) {
          joSensorData = audioRunnable.getAudioData(joSensorData);
          audioReading = true;
        }

        if (batteryLevel > 0) {
          joSensorData.put("battery_percentage", batteryLevel);
        }

        for (Float cursor : event.values) {
          if (!cursor.isNaN() && cursor < Long.MAX_VALUE && cursor > Long.MIN_VALUE) {
            sensorHierarchyName = event.sensor.getStringType().split("\\.");
            sensorName = (sensorHierarchyName.length == 0 ? event.sensor.getStringType() : sensorHierarchyName[sensorHierarchyName.length - 1]);
            joSensorData.put(sensorName, cursor);
          }
        }

        Log.e( logTag, "SensorRecorded!" );
        dbHelper.JsonToDatabase(joSensorData);
        serviceManager.sensorSuccess(true, gpsReading, audioReading);
        lastUpdate = System.currentTimeMillis();
        //Log.e( logTag, "Sensor EVENT!" );
      } catch (JSONException JsonEx) {
        Log.e(logTag, JsonEx.getMessage() + " || " + JsonEx.getCause());
      }
    }
  }

// Phone Sensors

  /** Use this method to control if we should be recording sensor data or not. */
  void setSensorLogging(boolean power) {
    if (power && !sensorsRegistered) {
      registerSensorListeners();
    }
    if (!power && sensorsRegistered) {
      unregisterSensorListeners();
    }
  }

  /**
   * A control method for collection intervals.
   */
  void setSensorRefreshTime(int updatedRefresh) {
    Log.e( logTag, "Set updateRefresh to: " + updatedRefresh );
    sensorRefreshTime = updatedRefresh;
  }

  /** Method to register listeners upon logging. */
  private void registerSensorListeners() {

    // Register each sensorMessageHandler to this activity.
    for (int cursorInt : usableSensorList) {
      mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(cursorInt),
              SensorManager.SENSOR_DELAY_NORMAL, null);
    }
    IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    passedContext.registerReceiver(this.batteryReceiver, batteryFilter, null, null);
    sensorsRegistered = true;
  }

  /** Unregister listeners. */
  private void unregisterSensorListeners() {
    if (sensorsRegistered) {
      passedContext.unregisterReceiver(this.batteryReceiver);
      mSensorManager.unregisterListener(this);
    }
    setGpsPower(false);
    setAudioPower(false);
    sensorsRegistered = false;
  }

  /** Generate a list of on-board phone sensors. */
  @TargetApi(21)
  private void parseSensorArray() {

    mSensorManager = (SensorManager) passedContext.getSystemService(Context.SENSOR_SERVICE);
    List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
    usableSensorList = new ArrayList<>(deviceSensors.size());

    for (Sensor i : deviceSensors) {
      // Use this to filter out trigger(One-shot) sensors, which are dealt with differently.
      if (i.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) {
        usableSensorList.add(i.getType());
      }
    }

    batteryReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        int batteryData = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int batteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (batteryData > 0 && batteryScale > 0) {
          batteryLevel = batteryData;
        }
      }
    };
  }

// GPS

  /** Control method to enable/disable gps recording. */
  void setGpsPower(boolean power) {
    Log.e( logTag, "GPS power: " + power );
    if (power && sensorLogging && !gpsRegistered) {
      registerGpsSensors();
    }

    if (!power && gpsRegistered) {
      unRegisterGpsSensors();
    }
  }


  /** Register gps sensors to enable recording. */
  private void registerGpsSensors() {

    boolean gpsPermissionFine = sharedPrefs.getBoolean("gps_permission_FINE", false);
    boolean gpsPermissionCoarse = sharedPrefs.getBoolean("gps_permission_COARSE", false);

    try {
      if (gpsPermissionFine || gpsPermissionCoarse) {
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, sensorRefreshTime - 10, 0, gpsLogger);
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, sensorRefreshTime - 10, 0, gpsLogger);
        Log.i(logTag, "GPS listeners registered.");
        gpsRegistered = true;
      } else {
        Log.e(logTag + "regGPS", "Register gps method, gpsPermissionFine == false");
      }
    } catch (SecurityException secEx) {
      Log.e(logTag, "Failure turning gps on/off. Cause: " + secEx.getMessage());
      secEx.printStackTrace();
    } catch (RuntimeException runTimeEx) {
      Log.e(logTag, "StackTrace: ");
      runTimeEx.printStackTrace();
    }
  }

  /** Unregister gps sensors. */
  private void unRegisterGpsSensors() {
    locationManager.removeUpdates(gpsLogger);
    gpsRegistered = false;
    Log.i(logTag, "GPS unregistered.");
  }

//AUDIO

  /** Set audio recording on/off. */
  void setAudioPower(boolean power) {
    if (power && sensorLogging && !audioRegistered) {
      registerAudioSensors();
    }
    if (!power && audioRegistered) {
      unregisterAudioSensors();
    }
  }

  /** Register audio recording thread. */
  private void registerAudioSensors() {
    audioRunnable = new AudioRunnable();
    threadPool.submit(audioRunnable);
    audioRegistered = true;
    Log.i(logTag, "Registered audio sensors.");
  }

  /** Stop audio recording thread. */
  private void unregisterAudioSensors() {
    audioRunnable.setStopAudioThread();
    audioRegistered = false;
    Log.i(logTag, "Unregistered audio sensors.");
  }


  /** Required stub. Not used. */
  @Override
  public final void onAccuracyChanged(Sensor sensor, int accuracy) {
  } // <- Empty

}

