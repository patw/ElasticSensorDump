package ca.dungeons.sensordump;

import android.annotation.TargetApi;
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
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Listener class to record sensorMessageHandler data.
 * @author Gurtok.
 * @version First version of upload Async thread.
 */
class SensorThread extends Thread implements SensorEventListener {

    private final String logTag = "SensorThread";

    /** Control variable to shut down thread. */
    private boolean stopSensorThread = false;

    /** Main activity context. */
    private Context passedContext;

    /** Gives access to the local database via a helper class.*/
    private DatabaseHelper dbHelper;

// Date / Time variables.
    /** A static reference to the custom date format. */
    @SuppressWarnings("SpellCheckingInspection")
    private static final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ", Locale.US);
    /** Timers, the schema is defined else where. */
    private long startTime, lastUpdate;

// Sensor variables.
    /** Instance of sensorMessageHandler Manager. */
    private SensorManager mSensorManager;
    /** Each loop, data wrapper to upload to Elastic. */
    private JSONObject joSensorData = new JSONObject();
    /** Array to hold sensorMessageHandler references. */
    private List<Integer> usableSensorList;
    /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;
    /** If listeners are active. */
    private boolean sensorsRegistered = false;
    /** Listener for battery updates. */
    private BroadcastReceiver batteryReceiver;
    /** Battery level in percentages. */
    private double batteryLevel = 0;

// GPS variables.
    /** Used to get access to GPS. */
    private LocationManager locationManager;
    /** Helper class to organize gps data. */
    private GPSLogger gpsLogger = new GPSLogger();
    /** Control variable to record gps data. */
    private boolean gpsRecording;
    /** Control for telling if we have already registered the gps listeners. */
    private boolean gpsRegistered;

// AUDIO variables.
    /** Helper class for obtaining audio data. */
    private AudioLogger audioLogger;
    /** Control variable to record audio data. */
    private boolean audioRecording;
    /** Control variable to make sure we only create one audio logger. */
    private boolean audioRegistered;

// Communications.
    /** Number of sensorMessageHandler readings this session, default 0. */
    private int sensorReadings = 0;
    /** Number of gps readings this session, default 0. */
    private int gpsReadings = 0;
    /** Number of audio events. */
    private int audioReadings = 0;

// Guts.
    /** Constructor:
     * Initialize the sensorMessageHandler manager.
     * Enumerate available sensors and store into a list.
     */
    SensorThread(Context context){
        passedContext = context;
        dbHelper = new DatabaseHelper( passedContext );
        locationManager = (LocationManager) passedContext.getSystemService( Context.LOCATION_SERVICE );
        startTime = lastUpdate = System.currentTimeMillis();
    }

    /** A control method for collection intervals. */
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
        if( !sensorsRegistered){
            registerSensorListeners();
            //Log.e( logTag, "SensorThread is running.");
        }
    }

    /** Our main connection to the UI thread for communication. */
    private void onProgressUpdate() {
        Intent messageIntent = new Intent( EsdServiceManager.UPDATE_UI_SENSOR_THREAD );
        messageIntent.putExtra("sensorReadings", sensorReadings );
        messageIntent.putExtra( "gpsReadings", gpsReadings );
        messageIntent.putExtra( "audioReadings", audioReadings );
        passedContext.sendBroadcast( messageIntent );
    }

      /**
       * This is the main recording loop. One reading per sensorMessageHandler per loop.
       * Update timestamp in sensorMessageHandler data structure.
       * Store the logging start time with each document.
       * Store the duration of the sensorMessageHandler log with each document.
       * Dump gps data into document if it's ready.
       * Put battery status percentage into the Json.
       *
       * @param event A reference to the event object.
       */
    @Override
    public final void onSensorChanged(SensorEvent event) {

        if( stopSensorThread ){

            unregisterSensorListeners();
            unRegisterGpsSensors();
            unregisterAudioSensors();
            onProgressUpdate();

        }else if( System.currentTimeMillis() > lastUpdate + sensorRefreshTime ) {
            // ^^ Make sure we generate docs at an adjustable rate.
            // 250ms is the default setting.

            // On each loop, check if we should be recording gps data.
            registerGpsSensors();

            // On each loop, check if we should be recording audio data.
            registerAudioSensors();

            String sensorName;
            String[] sensorHierarchyName;
            try {
                joSensorData.put("@timestamp", logDateFormat.format( new Date( System.currentTimeMillis() )) );
                joSensorData.put("start_time", logDateFormat.format( new Date( startTime ) ) );
                joSensorData.put("log_duration_seconds", ( System.currentTimeMillis() - startTime ) / 1000 );

                if( gpsRecording && gpsLogger.gpsHasData ){
                    joSensorData = gpsLogger.getGpsData( joSensorData );
                    gpsReadings++;
                }

                if( audioRecording && audioLogger.hasData ){
                    joSensorData = audioLogger.getAudioData( joSensorData );
                    audioReadings++;
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
                onProgressUpdate();
                lastUpdate = System.currentTimeMillis();
                //Log.e( logTag, "Sensor EVENT!" );
            } catch (JSONException JsonEx) {
                Log.e( logTag, JsonEx.getMessage() + " || " + JsonEx.getCause());
            }
        }
    }

    /** Required stub. Not used. */
    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy){} // <- Empty


// Phone Sensors

    /** Control method to shut down ALL sensor recording. */
    void stopSensorThread(){ stopSensorThread = true; }

    /** Method to register listeners upon logging. */
    private void registerSensorListeners(){
        parseSensorArray();
        // Register each sensorMessageHandler to this activity.
        for (int cursorInt : usableSensorList) {
            mSensorManager.registerListener( this, mSensorManager.getDefaultSensor(cursorInt),
                    SensorManager.SENSOR_DELAY_NORMAL, null);
        }
        IntentFilter batteryFilter = new IntentFilter( Intent.ACTION_BATTERY_CHANGED );
        passedContext.registerReceiver( this.batteryReceiver, batteryFilter, null, null);
        sensorsRegistered = true;
        //Log.e( logTag, "Registered listeners. ");
    }

    /** Unregister listeners. */
    private void unregisterSensorListeners(){
        if( sensorsRegistered ){
            passedContext.unregisterReceiver( this.batteryReceiver );
            mSensorManager.unregisterListener( this );
            sensorsRegistered = false;
            //Log.e( logTag, "Unregistered listeners. ");
        }
    }

    /** Generate a list of on-board phone sensors. */
    @TargetApi(21)
    private void parseSensorArray(){

        mSensorManager = (SensorManager) passedContext.getSystemService( Context.SENSOR_SERVICE );
        List<Sensor> deviceSensors = mSensorManager.getSensorList( Sensor.TYPE_ALL );
        usableSensorList = new ArrayList<>( deviceSensors.size() );
        for( Sensor i: deviceSensors ){
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


// GPS

    /** Control method to enable/disable gps recording. */
    void setGpsPower( boolean power ){
        gpsRecording = power;
    }


    /** Register gps sensors to enable recording. */
    private void registerGpsSensors(){
        if ( gpsRecording && !gpsRegistered ) {
            try{
                gpsLogger = new GPSLogger();
                locationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, sensorRefreshTime, 0, gpsLogger );
                Log.i( logTag, "GPS listeners registered.");
                gpsRegistered = true;
            }catch ( SecurityException secEx ) {
                Log.e( logTag, "Failure turning gps on/off. Cause: " + secEx.getMessage() );
            }catch( RuntimeException runTimeEx ){
                Log.e( logTag, "StackTrace: " );
                runTimeEx.printStackTrace();
            }

        }
    }

    /** Unregister gps sensors. */
    private void unRegisterGpsSensors(){
        if( gpsRegistered ){
            gpsRecording = false;
            gpsRegistered = false;
            locationManager.removeUpdates( gpsLogger );
            Log.i( logTag, "GPS unregistered.");
        }
    }

//AUDIO
    /** Set audio recording on/off. */
    void setAudioPower( boolean power ){ audioRecording = power; }

    /** Use this method for periodic changes to audio recording from UI thread toggle button. */
    private void checkAudioAccess(){
        if( audioRecording && !audioRegistered ){
            registerAudioSensors();
        }else if( !audioRecording && audioRegistered ){
            unregisterAudioSensors();
        }
    }

    /** Register audio recording thread. */
    private void registerAudioSensors(){
        if( !audioRegistered ){
            audioLogger = new AudioLogger();
            audioLogger.start();
            audioRegistered = true;
            Log.i( logTag, "Registered audio sensors." );
        }
    }

    /** Stop audio recording thread. */
    private void unregisterAudioSensors(){
        if( audioRegistered ){
            audioRegistered = false;
            audioLogger.setStopAudioThread(true);
            audioLogger = null;
            Log.i( logTag, "Unregistered audio sensors." );
        }
    }



}
