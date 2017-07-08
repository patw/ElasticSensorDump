package ca.dungeons.sensordump;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class SensorService extends Service implements SensorEventListener {

    /** Unique ID for broadcasting information to UI thread. */
    static final int SENSOR_THREAD_ID = 654321;

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
    /** Reference handler to send messages back to the UI thread. */
    private Handler uiHandler;
    /** Number of sensorMessageHandler readings this session, default 0. */
    private static int sensorReadings = 0;
    /** Number of gps readings this session, default 0. */
    private static int gpsReadings = 0;


// Guts.
    /** Constructor:
     * Initialize the sensorMessageHandler manager.
     * Enumerate available sensors and store into a list.
     */
    SensorService(Context context, Handler passedHandler) {
        passedContext = context;
        uiHandler = passedHandler;
        dbHelper = new DatabaseHelper( passedContext );
        locationManager = (LocationManager) passedContext.getSystemService( Context.LOCATION_SERVICE );
        startTime = lastUpdate = System.currentTimeMillis();
    }

    /** A control method for collection intervals. */
    void setSensorRefreshTime(int updatedRefresh ){ sensorRefreshTime = updatedRefresh; }

    /** Control method to shut down ALL sensor recording. */
    void stopSensorThread(){ stopSensorThread = true; }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return flags;
    }


    @Override
    public final void onSensorChanged( SensorEvent event ){

        if( stopSensorThread ){
            onProgressUpdate();
            if(sensorsRegistered) {
                unregisterSensorListeners();
                if( gpsRegistered )
                    unRegisterGpsSensors();
                if( audioRegistered )
                    unregisterAudioSensors();
            }
        }else if( System.currentTimeMillis() > lastUpdate + sensorRefreshTime ) {
            // ^^ Make sure we generate docs at an adjustable rate.
            // 250ms is the default setting.

            // On each loop, check if we should be recording gps data.
            checkGpsAccess();

            // On each loop, check if we should be recording audio data.
            checkAudioAccess();

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
            } catch (JSONException JsonEx) {
                Log.e("Sensors-sensorChanged", JsonEx.getMessage() + " || " + JsonEx.getCause());
            }
        }

    }


// Phone Sensors
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
        Log.e("Sensors", "Registered listeners. ");
    }

    /** Unregister listeners. */
    private void unregisterSensorListeners(){
        passedContext.unregisterReceiver( this.batteryReceiver );
        mSensorManager.unregisterListener( this );
        sensorsRegistered = false;
        Log.e("Sensors", "Unregistered listeners. ");
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
    /** Register gps sensors to enable recording. */
    private void registerGpsSensors(){
        try{
            if( !gpsRegistered ){
                locationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, sensorRefreshTime, 0, gpsLogger );
                Log.i("Sensors-GPS Power", "GPS listeners registered.");
                gpsRegistered = true;
            }
        }catch ( SecurityException secEx ) {
            Log.e( "Sensors-GPS Power", "Failure turning gps on/off. Cause: " + secEx.getMessage() );
        }catch( RuntimeException runTimeEx ){
            Log.e( "Sensors-GPS Power", "StackTrace: " );
            runTimeEx.printStackTrace();
        }
    }

    /** Unregister gps sensors. */
    private void unRegisterGpsSensors(){
        locationManager.removeUpdates( gpsLogger );
        gpsRecording = gpsRegistered = false;
        Log.i("Sensors-UnregisterGPS", "GPS unregistered.");
    }

    /** Control method to enable/disable gps recording. */
    void setGpsPower( boolean power ){
        gpsRecording = power;
    }

    /** Use this method for periodic changes to gps access from UI thread toggle button. */
    private void checkGpsAccess(){
        if( gpsRecording && !gpsRegistered ){
            registerGpsSensors();
        }else if( !gpsRecording && gpsRegistered ){
            unRegisterGpsSensors();
        }
    }


//AUDIO
    /** Set audio recording on/off. */
    void setAudioPower( boolean power ){ audioRecording = power; }

    /** Register audio recording thread. */
    private void registerAudioSensors(){
        audioLogger = new AudioLogger();
        if( !audioLogger.isAlive() && !audioRegistered){
            audioLogger.start();
            audioRegistered = true;
            Log.i("Sensors-RegisterAudio", "Registered audio sensors." );
        }
    }

    /** Stop audio recording thread. */
    private void unregisterAudioSensors(){
        if(audioRegistered){
            audioLogger.setStopAudioThread(true);
            audioRegistered = false;
            Log.i("Sensors-UnregisterAudio", "Unregistered audio sensors." );
        }
    }

    /** Use this method for periodic changes to audio recording from UI thread toggle button. */
    private void checkAudioAccess(){
        if( audioRecording && !audioRegistered ){
            registerAudioSensors();
        }else if( !audioRecording && audioRegistered ){
            unregisterAudioSensors();
        }
    }


//BINDS
    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        return super.bindService(service, conn, flags);
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        super.unbindService(conn);
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }



// POWER BUTTONS
    @Override
    public ComponentName startService(Intent service) {


        return super.startService(service);
    }

    @Override
    public boolean stopService(Intent name) {


        return super.stopService(name);
    }

    @Override
    public void onDestroy() {


        super.onDestroy();
    }






// Message passing.
    /** If the main application is active and is the users focus, update our counts continuously. */
    private void onProgressUpdate() {
        Message outMessage = uiHandler.obtainMessage();
        outMessage.arg1 = sensorReadings;
        outMessage.arg2 = gpsReadings;
        outMessage.what = SENSOR_THREAD_ID;
        uiHandler.sendMessage(outMessage);
    }




    /** Not used. */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }







}
