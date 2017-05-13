package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.location.LocationManager;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Listener class to record sensor data.
 * @author Gurtok.
 * @version First version of upload Async thread.
 */
class SensorThread extends Thread implements SensorEventListener {

      /** Each loop, data wrapper to upload to Elastic. */
    private JSONObject joSensorData = new JSONObject();

      /** Helper class to organize gps data. */
    private static GPSLogger gpsLogger = new GPSLogger();

    private static LocationManager locationManager;

      /** Main activity context. */
    private Context passedContext;

      /** Reference handler to send messages back to the UI thread. */
    private Handler uiHandler;

    private Handler sensorHandler;

      /** Instance of sensor Manager. */
    private SensorManager mSensorManager;

    private BatteryManager batteryManager;

      /** Gives access to the local database via a helper class.*/
    private DatabaseHelper dbHelper;

      /** Array to hold sensor references. */
    private List<Integer> usableSensorList;

      /** Battery level in percentages. */
    private double batteryLevel = 0;

      /** Timers, the schema is defined else where. */
    private long startTime, lastUpdate;

      /** True if we are able to read gps sensors */
    private static boolean gpsLogging = false;

    private boolean listenersRegistered = false;

      /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;

      /** Number of sensor readings this session, default 0. */
    private static long sensorReadings = 0;
    /** Number of documents sent to server this session, default 0. */
    private static long documentsIndexed = 0;
    /** Number of gps readings this session, default 0. */
    private static long gpsReadings = 0;
    /** Number of failed upload transactions this session, default 0. */
    private static long uploadErrors = 0;

    /** Boolean to indicate if we should be recording data. */
    private boolean logging = false;

    /** Boolean to help shut down the thread when required. */
    private boolean stopThread = false;

    /** Listener for battery updates. */
    private BroadcastReceiver batteryReceiver;


      /** Constructor:
       * Initialize the sensor manager.
       * Enumerate available sensors and store into a list.
       */
    SensorThread(Context context, Handler passedHandler) {
        passedContext = context;
        uiHandler = passedHandler;
        sensorHandler = new Handler();
        locationManager = (LocationManager) passedContext.getSystemService( Context.LOCATION_SERVICE );
        dbHelper = new DatabaseHelper( passedContext );
        startTime = lastUpdate = System.currentTimeMillis();
        parseSensorList();
    }

    void setLogging(boolean enableLogging){
        if(enableLogging){
            registerListeners();
        }else{
            logging = false;
        }

    }

    void setGPSlogging( boolean enableGPS){
        gpsLogging = enableGPS;
    }

    void setSensorRefreshTime(int updatedRefresh ){ sensorRefreshTime = updatedRefresh; }


    /** Main work here:
     * Spin up message thread for this thread with Looper.
     * Set up
     * Time the messages to be every 250ms, instead of unlimited.
     * UI thread interrupts this thread, which throws an
     *    InterruptException to unregister the listeners.
     */
    @Override
    public void run() {
        Looper.prepare();
        Looper.loop();
    }

    /** Setter method for the number of documents successfully uploaded to Elastic. */
    static void incrementDocumentsIndexed(){ documentsIndexed++; }
      /** Setter method for the number of GPS readings recorded from GPSLogger. */
    static void incrementGpsReadings(){
        gpsReadings++;
    }
      /** Setter method for the number of documents that failed to be uploaded. */
    static void incrementUploadErrors(){ uploadErrors++; }


      /**
       * This is the main recording loop. One reading per sensor per loop.
       * Update timestamp in sensor data structure.
       * Store the logging start time with each document.
       * Store the duration of the sensor log with each document.
       * Dump gps data into document if it's ready.
       * Put battery status percentage into the Json.
       *
       * @param event A reference to the event object.
       */
    @Override
    public final void onSensorChanged(SensorEvent event) {

        /* Control variable to help stop the thread when not needed. */
        if( stopThread ){
            Thread thisThread = Thread.currentThread();
            // UploadAsyncTask needs to be informed to finish as well.
            unregisterSensorListeners();
            thisThread.interrupt();
            return;
        }

        if( logging ) {
            Log.e("SensorThread", "Sensor Event: " + event.toString());
            try {
                Date logDate = new Date(System.currentTimeMillis());

                @SuppressWarnings("SpellCheckingInspection")
                SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ", Locale.US);
                String dateString = logDateFormat.format(logDate);
                joSensorData.put("@timestamp", dateString);

                Date startDate = new Date(startTime);
                String startDateString = logDateFormat.format(startDate);
                joSensorData.put("start_time", startDateString);

                long logDuration = (System.currentTimeMillis() - startTime) / 1000;
                joSensorData.put("log_duration_seconds", logDuration);

                if (gpsLogger.gpsHasData) {
                    // Function to update the joSensorData list.
                    joSensorData.put("location", "" + gpsLogger.gpsLat + "," + gpsLogger.gpsLong);
                    joSensorData.put("start_location", "" + gpsLogger.gpsLatStart + "," + gpsLogger.gpsLongStart);
                    joSensorData.put("altitude", gpsLogger.gpsAlt);
                    joSensorData.put("accuracy", gpsLogger.gpsAccuracy);
                    joSensorData.put("bearing", gpsLogger.gpsBearing);
                    joSensorData.put("gps_provider", gpsLogger.gpsProvider);
                    joSensorData.put("speed", gpsLogger.gpsSpeed);
                    joSensorData.put("speed_kmh", gpsLogger.gpsSpeedKMH);
                    joSensorData.put("speed_mph", gpsLogger.gpsSpeedMPH);
                    joSensorData.put("gps_updates", gpsLogger.gpsUpdates);
                    joSensorData.put("acceleration", gpsLogger.gpsAcceleration);
                    joSensorData.put("acceleration_kmh", gpsLogger.gpsAccelerationKMH);
                    joSensorData.put("acceleration_mph", gpsLogger.gpsAccelerationMPH);
                    joSensorData.put("distance_metres", gpsLogger.gpsDistanceMetres);
                    joSensorData.put("distance_feet", gpsLogger.gpsDistanceFeet);
                    joSensorData.put("total_distance_metres", gpsLogger.gpsTotalDistance);
                    joSensorData.put("total_distance_km", gpsLogger.gpsTotalDistanceKM);
                    joSensorData.put("total_distance_miles", gpsLogger.gpsTotalDistanceMiles);
                }

                if (batteryLevel > 0) {
                    joSensorData.put("battery_percentage", batteryLevel);
                }

                // Store sensor update into sensor data structure
                for (int i = 0; i < event.values.length; i++) {
                    // We don't need the android.sensor. and motorola.sensor. stuff
                    // Split it out and just get the sensor name
                    String sensorName;
                    String[] sensorHierarchyName = event.sensor.getStringType().split("\\.");
                    if (sensorHierarchyName.length == 0) {
                        sensorName = event.sensor.getStringType();
                    } else {
                        sensorName = sensorHierarchyName[sensorHierarchyName.length - 1] + i;
                    }
                    // Store the actual sensor data now unless it's returning NaN or something crazy big or small
                    Float sensorValue = event.values[i];
                    if (!sensorValue.isNaN() && sensorValue < Long.MAX_VALUE && sensorValue > Long.MIN_VALUE) {
                        joSensorData.put(sensorName, sensorValue);
                    }
                }
                // Make sure we only generate docs at an adjustable rate.
                // 250ms is the default setting.
                if (System.currentTimeMillis() > lastUpdate + sensorRefreshTime) {
                    dbHelper.JsonToDatabase(joSensorData);
                    sensorReadings++;
                    lastUpdate = System.currentTimeMillis();

                    Bundle dataBundle = new Bundle(5);
                    dataBundle.putLong("sensorReadings", sensorReadings);
                    dataBundle.putLong("documentsIndexed", documentsIndexed);
                    dataBundle.putLong("gpsReadings", gpsReadings);
                    dataBundle.putLong("uploadErrors", uploadErrors);
                    dataBundle.putLong("databaseEntries", dbHelper.databaseEntries());
                    Message outMessage = uiHandler.obtainMessage();
                    outMessage.setData(dataBundle);
                    uiHandler.sendMessage(outMessage);
                }

            } catch (JSONException e) {
                Log.e("JSON Logging error", e.getMessage() + " space " + e.getCause());
            }
        }


    }

    /**
     * Required stub. Not used.
     * @param sensor Not used.
     * @param accuracy Not used.
     */
    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy){
    }

    /**
     * Power button for gps recording.
     * @param power True if we should be recording gps data. False to remove gps listeners.
     */
    private void gpsPower(boolean power){
        try{
            if( power && !gpsLogging ){
                locationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, sensorRefreshTime, 0, gpsLogger );
                gpsLogging = true;
            }else if( !power ){
                locationManager.removeUpdates( gpsLogger );
                gpsLogging = false;
            }
        } catch ( SecurityException SecEx ) {
            Log.e( "GPS Power", "Failure turning gps on/off." );
        }
    }

    /** Generate array with sensor IDs to reference. */
    private void parseSensorList(){
        mSensorManager = (SensorManager) passedContext.getSystemService( Context.SENSOR_SERVICE );
        List<Sensor> deviceSensors = mSensorManager.getSensorList( Sensor.TYPE_ALL );
        usableSensorList = new ArrayList<>( deviceSensors.size() );
        for( Sensor i: deviceSensors ){
            usableSensorList.add( i.getType() );
        }
    }

    /** Method to register listeners upon logging. */
    private void registerListeners(){
        if( ! listenersRegistered ) {
            // Register each sensor to this activity.
            for (int cursorInt : usableSensorList) {
                mSensorManager.registerListener( this, mSensorManager.getDefaultSensor(cursorInt),
                        SensorManager.SENSOR_DELAY_NORMAL, sensorHandler );
            }
            IntentFilter batteryFilter = new IntentFilter( Intent.ACTION_BATTERY_CHANGED );
            passedContext.registerReceiver( this.batteryReceiver, batteryFilter, null, sensorHandler);
            gpsPower( true );
            listenersRegistered = true;
        }
    }

    private void unregisterSensorListeners(){
        if( listenersRegistered ){
            passedContext.unregisterReceiver( this.batteryReceiver );
            mSensorManager.unregisterListener( this );
            gpsPower( false );
            listenersRegistered = false;
        }
    }


}
