package ca.dungeons.sensordump;


import android.content.Context;
import android.location.LocationManager;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


/**
 * Created by Gurtok on 4/21/2017.
 *
 */
class SensorThread extends Thread implements SensorEventListener {

      /** Each loop, data wrapper to upload to Elastic. */
    private JSONObject joSensorData = new JSONObject();

      /** Timers, the schema is defined else where. */
    private long startTime, lastUpdate;

      /** Object to ease the transfer of updated data to the UI thread. */
    private Message outMessage;

    private Handler uiHandler;

      /** Battery level in percentages. */
    double batteryLevel = 0;

      /** Gives access to the local database via a helper class.*/
    private DatabaseHelper dbHelper;

      /** True if we are currently reading from gps sensors */
    private static boolean gpsLogging = false;

      /** Refresh time in milliseconds. Default = 250ms.*/
    int sensorRefreshTime = 250;

      /** Instance of gps Manager. */
    private static LocationManager locationManager;

      /** Main activity context. */
    private Context passedContext;

       /** A new instance of the GPSLogger.java file.
        * Helper class to organize gps data. */
    private static GPSLogger gpsLogger = new GPSLogger();

      /** Number of sensor readings this session, default 0. */
    private static long sensorReadings, documentsIndexed, gpsReadings,
                        uploadErrors, databaseEntries = 0;


      /** Control variable to signal when to shut down the thread. */
    private boolean exitThread = false;

      /** Constructor:
       * Initialize the sensor manager.
       * Enumerate available sensors and store into a list.
       */
    SensorThread(Context context, Handler passedHandler) {
        passedContext = context;
        uiHandler = passedHandler;
        dbHelper = new DatabaseHelper( passedContext );
        startTime = lastUpdate = System.currentTimeMillis();
    }

    void setExitThread(boolean threadPower) {
        exitThread = threadPower;
    }

    @Override
    public void run() {
        Looper.prepare();
        Looper.loop();
        while( !exitThread ){
            updateUiThread();
        }
    }

    /** Setter method for the number of documents successfully uploaded to Elastic. */
    static void incrementDocumentsIndexed(){
        documentsIndexed++;
    }
      /** Setter method for the number of GPS readings recorded from GPSLogger. */
    static void incrementGpsReadings(){
        gpsReadings++;
    }
      /** Setter method for the number of documents that failed to be uploaded. */
    static void incrementUploadErrors(){
        uploadErrors++;
    }
      /** Setter method for the number of documents stored internally in database. */
    static void setDatabaseEntries( long population ){
        databaseEntries = population;
    }

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

            if ( batteryLevel > 0 ){
                joSensorData.put( "battery_percentage", batteryLevel );
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
                updateUiThread();
            }
        } catch (JSONException e) {
            Log.e("JSON Logging error", e.getMessage() + " space " + e.getCause() );
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
    void gpsPower(boolean power) {
        //Turn on gps logging.
        if ( power && !gpsLogging ) {// Light up the GPS if we're allowed
            locationManager = (LocationManager) passedContext.getSystemService(Context.LOCATION_SERVICE);
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
                gpsLogging = true;
            } catch (SecurityException e) {
                Log.e("GPS Power", "Failure powering on gpsRecording.");
            }
            return;
        }
        // Turn off gps logging.
        if( !power && gpsLogging ){ //unbind GPS listener if permission was granted && we are logging
            try{
                locationManager.removeUpdates(gpsLogger);
                gpsLogging = false;
            }catch (SecurityException e) {
                Log.e("gpsPower", "Error shutting gps logging down.");
            }
        }
    }

    /**
     * Update the display with readings/written/errors.
     * Need to update UI based on the passed data intent.
     *
     * Set up messageHandling to relay new information to the UI thread.
     */
    private void updateUiThread() {
        Bundle dataBundle = new Bundle(5);
        dataBundle.putLong("sensorReadings", sensorReadings);
        dataBundle.putLong( "documentsIndexed", documentsIndexed);
        dataBundle.putLong( "gpsReadings", gpsReadings);
        dataBundle.putLong( "uploadErrors", uploadErrors);
        dataBundle.putLong( "databaseEntries", databaseEntries);
        outMessage.setData(dataBundle);
    }


}
