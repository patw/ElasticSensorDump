package ca.dungeons.sensordump;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.LocationManager;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import android.os.AsyncTask;
import android.os.BatteryManager;
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
class SensorThread extends AsyncTask<Void,Void,Void> implements SensorEventListener {

    static final int SENSOR_THREAD_ID = 654321;
        @SuppressWarnings("SpellCheckingInspection")
    private static final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ", Locale.US);
        /** Each loop, data wrapper to upload to Elastic. */
    private JSONObject joSensorData = new JSONObject();
        /** Helper class to organize gps data. */
    private GPSLogger gpsLogger;

        /** Used to get access to GPS. */
    private LocationManager locationManager;
        /** Main activity context. */
    private Context passedContext;
        /** Reference handler to send messages back to the UI thread. */
    private Handler uiHandler;
        /** Handler for sensor data. */
    private Handler sensorHandler;
        /** Instance of sensor Manager. */
    private SensorManager mSensorManager;
        /** Gives access to the local database via a helper class.*/
    private DatabaseHelper dbHelper;
        /** Array to hold sensor references. */
    private List<Integer> usableSensorList;
        /** Listener for battery updates. */
    private BroadcastReceiver batteryReceiver;
        /** Control variable to record gps data. */
    private boolean gpsRecording;
    /** Control for telling if we have already registered the gps listeners. */
    private boolean gpsRegistered;
    /** Battery level in percentages. */
    private double batteryLevel = 0;
    /** Timers, the schema is defined else where. */
    private long startTime, lastUpdate;
    /** If listeners are active. */
    private boolean listenersRegistered = false;
    /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;
    /** Number of sensor readings this session, default 0. */
    private static int sensorReadings = 0;
    /** Number of gps readings this session, default 0. */
    private static int gpsReadings = 0;

    void setSensorRefreshTime(int updatedRefresh ){ sensorRefreshTime = updatedRefresh; }

      /** Constructor:
       * Initialize the sensor manager.
       * Enumerate available sensors and store into a list.
       */
    SensorThread(Context context, Handler passedHandler) {
        passedContext = context;
        uiHandler = passedHandler;
        sensorHandler = new Handler();

        dbHelper = new DatabaseHelper( passedContext );
        startTime = lastUpdate = System.currentTimeMillis();
    }

    /** Main work here:
     * Spin up message thread for this thread with Looper.
     * Set up
     * Time the messages to be every 250ms, instead of unlimited.
     * UI thread interrupts this thread, which throws an
     *    InterruptException to unregister the listeners.
     */

    @Override
    protected Void doInBackground(Void... params) {
        Log.e("SensorThread", "doInBackground.");
        Looper.prepare();
        if( this.isCancelled() ){
            unregisterListeners();
            return null;
        }else if( listenersRegistered ){
            unregisterListeners();
        }
        registerListeners();
        Looper.loop();
        return null;
    }

    /** RUNS ON UI THREAD!
     *  Generate array with sensor IDs to reference. */
    @Override
    protected void onPreExecute(){}

    /** RUNS ON UI THREAD! */
    @Override
    protected void onPostExecute(Void aVoid){}

    @Override
    protected void onProgressUpdate(Void...params) {
        Message outMessage = uiHandler.obtainMessage();
        outMessage.arg1 = sensorReadings;
        outMessage.arg2 = gpsReadings;
        outMessage.what = SENSOR_THREAD_ID;
        uiHandler.sendMessage(outMessage);
        Log.i("SensorThread", "Progress message sent.");
    }

    /** RUNS ON UI THREAD! */
    @Override
    protected void onCancelled() {

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

        if( this.isCancelled() ){
            if( listenersRegistered ){
                unregisterListeners();
            }
            if( gpsRegistered ){
                unRegisterGpsSensors();
            }

        }else if( System.currentTimeMillis() > lastUpdate + sensorRefreshTime ) {
            // Make sure we only generate docs at an adjustable rate.
            // 250ms is the default setting.

            // On each loop, check if we should be recording gps data.
            checkGpsAccess();

            // Log.e("SensorThread", "SENSOR EVENT"); // For diagnostics.
            String sensorName;
            String[] sensorHierarchyName;
            try {
                joSensorData.put("@timestamp", logDateFormat.format( new Date(System.currentTimeMillis())) );
                joSensorData.put("start_time", logDateFormat.format( new Date( startTime ) ) );
                joSensorData.put("log_duration_seconds", ( System.currentTimeMillis() - startTime ) / 1000 );

                if( gpsRecording && gpsLogger.gpsHasData ){
                    joSensorData = gpsLogger.getGpsData( joSensorData );
                    gpsReadings++;
                }

                if( batteryLevel > 0 ){
                    joSensorData.put("battery_percentage", batteryLevel);
                }

                for( Float cursor: event.values ){
                    if( !cursor.isNaN() && cursor < Long.MAX_VALUE && cursor > Long.MIN_VALUE ){
                        sensorHierarchyName = event.sensor.getStringType().split("\\.");
                        sensorName = ( sensorHierarchyName.length == 0 ? event.sensor.getStringType() : sensorHierarchyName[sensorHierarchyName.length - 1] );
                        joSensorData.put(sensorName, cursor );
                    }
                }

                dbHelper.JsonToDatabase( joSensorData );
                sensorReadings++;
                lastUpdate = System.currentTimeMillis();
                onProgressUpdate();

            } catch (JSONException JsonEx) {
                Log.e("JSON Logging error", JsonEx.getMessage() + " || " + JsonEx.getCause());
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


    /** Method to register listeners upon logging. */
    private void registerListeners(){
        if( !listenersRegistered ) {
            parseSensorArray();
            // Register each sensor to this activity.
            for (int cursorInt : usableSensorList) {
                mSensorManager.registerListener( this, mSensorManager.getDefaultSensor(cursorInt),
                        SensorManager.SENSOR_DELAY_NORMAL, sensorHandler );
            }
            IntentFilter batteryFilter = new IntentFilter( Intent.ACTION_BATTERY_CHANGED );
            passedContext.registerReceiver( this.batteryReceiver, batteryFilter, null, sensorHandler);
            listenersRegistered = true;
            Log.e("SensorThread", "Registered listeners. ");
        }else{
            Log.e("SensorThread", "Error, listeners registered illegally.");
        }

    }

    /** Unregister listeners. */
    private void unregisterListeners(){
        if( listenersRegistered ){
            passedContext.unregisterReceiver( this.batteryReceiver );
            mSensorManager.unregisterListener( this );
            listenersRegistered = false;
            Log.e("SensorThread", "Unregistered listeners. ");
        }
    }

    @TargetApi(21)
    private void parseSensorArray(){

        mSensorManager = (SensorManager) passedContext.getSystemService( Context.SENSOR_SERVICE );
        List<Sensor> deviceSensors = mSensorManager.getSensorList( Sensor.TYPE_ALL );
        usableSensorList = new ArrayList<>( deviceSensors.size() );
        for( Sensor i: deviceSensors ){
            //Log.e("SensorThread", i.getName() );
            // Use this to filter out trigger(One-shot) sensors, which are dealt with differently.
            if( i.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT ){
                usableSensorList.add( i.getType() );
            }
        }

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int batteryData = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int batteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if ( batteryData > 0 && batteryScale > 0 ) {
                    batteryLevel = batteryData;
                }
            }
        };

    }

    // Register gps sensors to enable recording.
    private void registerGpsSensors(){
        gpsLogger = new GPSLogger();
        locationManager = (LocationManager) passedContext.getSystemService( Context.LOCATION_SERVICE );
        try{
            locationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, sensorRefreshTime, 0, gpsLogger );
        }catch ( SecurityException SecEx ) {
            Log.e( "GPS Power", "Failure turning gps on/off." );
        }catch( RuntimeException runTimeEx ){
            Log.e( "GPS Power", "StackTrace: " );
            runTimeEx.printStackTrace();
        }
    }

    // Unregister gps sensors
    private void unRegisterGpsSensors(){
        locationManager.removeUpdates( gpsLogger );
        gpsRecording = gpsRegistered = false;
    }

    // Control method to enable/disable gps recording.
    void setGpsPower( boolean power ){
        gpsRecording = power;
    }

    private void checkGpsAccess(){
        if( gpsRecording && !gpsRegistered ){
            registerGpsSensors();
        }else if( !gpsRecording && gpsRegistered ){
            unRegisterGpsSensors();
        }
    }

}
