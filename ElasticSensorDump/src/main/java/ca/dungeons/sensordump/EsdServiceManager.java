package ca.dungeons.sensordump;


import android.app.Service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ca.dungeons.sensordump.MainActivity.sharedPrefs;

public class EsdServiceManager extends Service {

    private static final String logTag = "EsdServiceManager";

    private static ExecutorService threadPool;

    /** Use SensorThread class to start the logging process. */
    private SensorThread sensorThread;
    /** UploadThread controls the data flow between the local database and Elastic server. */
    private UploadThread uploadThread;



    /** True if we are currently reading sensor data. */
    public static boolean logging = false;
    private boolean audioLogging = false;
    private boolean gpsLogging = false;

    private int sensorRefreshRate = 250;

    /** These are the different actions that the receiver can manage. */
    public final static String SENSOR_MESSAGE = "esd.intent.action.message.SENSOR";
    public final static String GPS_MESSAGE = "esd.intent.action.message.GPS";
    public final static String AUDIO_MESSAGE = "esd.intent.action.message.AUDIO";
    public static String INTERVAL = "esd.intent.action.message.INTERVAL";
    public final static String UPDATE_UI_UPLOAD_TASK = "esd.intent.action.message.UPDATE_UI_UPLOAD_TASK";
    public final static String UPDATE_UI_SENSOR_THREAD = "esd.intent.action.message.UPDATE_UI_SENSOR_THREAD";

    /** Number of sensor readings this session */
    public int sensorReadings, documentsIndexed, gpsReadings,
                uploadErrors, audioReadings, databasePopulation = 0;

    public EsdServiceManager(){
        setupUploadTimer();
        setupManagerTimeout();
    }

    @Override
    public void onCreate () {
        // Cache our pool to execute the SensorThread and UploadThread on.
        threadPool = Executors.newFixedThreadPool(2);

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences( getBaseContext() );


        uploadThread = new UploadThread( getApplicationContext(), sharedPrefs );
        uploadThread.start();

        sensorThread = new SensorThread( getApplicationContext(), sharedPrefs );
        sensorThread.start();
    }


    @Override
    public int onStartCommand (Intent intent,int flags, int startId){
        //Log.e(logTag, "ESD -- On Start Command." );
        if( ! MainActivity.serviceManagerRunning ){
            Runnable serviceRunnable = new Runnable() {
                @Override
                public void run() {
                    registerMessageReceiver();
                    Intent messageIntent = new Intent(MainActivity.UI_ACTION_RECEIVER);
                    messageIntent.putExtra("serviceManagerRunning", true );
                    sendBroadcast( messageIntent );
                }

            };

            Thread serviceThread = new Thread( serviceRunnable );
            serviceThread.start();

        }
        Log.i(logTag, "Started service manager.");
        return Service.START_NOT_STICKY;
    }


    private void registerMessageReceiver(){

        IntentFilter filter = new IntentFilter();
        filter.addAction(SENSOR_MESSAGE);
        filter.addAction(INTERVAL);
        filter.addAction(AUDIO_MESSAGE );
        filter.addAction(GPS_MESSAGE);
        filter.addAction( UPDATE_UI_SENSOR_THREAD );
        filter.addAction( UPDATE_UI_UPLOAD_TASK );

        BroadcastReceiver receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {

                // Intent action to start recording phone sensors.
                if( intent.getAction().equals( SENSOR_MESSAGE )){
                    if( intent.getBooleanExtra( "sensorPower", true ) ){
                        startLogging();
                    }else{
                        stopLogging();
                    }
                }

                // Intent action to start gps recording.
                if( intent.getAction().equals( GPS_MESSAGE ) ){
                    gpsLogging = intent.getBooleanExtra("gpsPower", false );
                    sensorThread.setGpsPower( gpsLogging );
                }

                // Intent action to start frequency recording.
                if( intent.getAction().equals( AUDIO_MESSAGE )){
                    audioLogging = intent.getBooleanExtra("audioPower", false );
                    sensorThread.setAudioPower( audioLogging );
                }

                // Receiver to adjust the sensor collection interval.
                if( intent.getAction().equals( INTERVAL ) ){
                    sensorRefreshRate = intent.getIntExtra( "sensorInterval", 250 ) ;
                    sensorThread.setSensorRefreshTime( sensorRefreshRate );
                }

                if( intent.getAction().equals( UPDATE_UI_SENSOR_THREAD ) ){
                    sensorReadings = intent.getIntExtra("sensorReadings", sensorReadings );
                    gpsReadings = intent.getIntExtra( "gpsReadings", gpsReadings );
                    audioReadings = intent.getIntExtra( "audioReadings", audioReadings );
                    updateUiData( "sensor" );
                }

                if( intent.getAction().equals( UPDATE_UI_UPLOAD_TASK ) ){
                    documentsIndexed = intent.getIntExtra( "documentsIndexed", documentsIndexed );
                    uploadErrors = intent.getIntExtra( "uploadErrors", uploadErrors );
                    databasePopulation = intent.getIntExtra( "databasePopulation", databasePopulation );
                    updateUiData( "upload" );
                }

            }
        };

    getApplicationContext().registerReceiver( receiver, filter );
    }

    /** This method uses the passed UI handler to relay messages if/when the activity is running. */
    void updateUiData( String verb ){

        if( getApplicationContext() != null ){
            Intent outIntent = new Intent( MainActivity.UI_DATA_RECEIVER );

            if( verb.equals( "sensor" ) ){
                outIntent.putExtra( "sensorReadings", sensorReadings );
                outIntent.putExtra( "gpsReadings", gpsReadings );
                outIntent.putExtra( "audioReadings", audioReadings );
            }else if( verb.equals( "upload" ) ){
                outIntent.putExtra( "documentsIndexed", documentsIndexed );
                outIntent.putExtra( "uploadErrors", uploadErrors );
                outIntent.putExtra( "databasePopulation", databasePopulation );
            }
            outIntent.putExtra( "verb", verb );
            sendBroadcast( outIntent );
        }
    }


    /** Timer used to periodically check if the upload task needs to be run. */
    void setupUploadTimer() {
        final Timer uploadTimer = new Timer();
        uploadTimer.schedule(new TimerTask() {
            public void run() {
                if( !uploadThread.isAlive() )
                uploadThread.start();
            }
        }, 500, 30000);
    } // Delay the task 5 seconds out and then repeat every 30 seconds.

    /** A timer to check if the service manager is running without logging. Check this once per hour.
     * When the service is in the background and logging, it will live past the activity life cycle.
     */
    void setupManagerTimeout(){
        Timer serviceTimer = new Timer();
        serviceTimer.schedule(new TimerTask() {
            public void run() {

                if( MainActivity.serviceManagerRunning && !logging ){
                    Log.e( logTag, "Shutting down service. Not logging!" );
                    this.cancel();
                }
            }
        }, 1000*60, 1000*60*30);
    } // Delay the task 5 seconds out and then repeat once every 30 min.

    /**
     * Start logging method:
     * 1. Bind sensor array to activity with a listener.
     * 2. Bind battery listener to activity.
     * 3. Clear out old data counts.
     * 4. Reset the gpsLogger counts.
     * 5. Send true to gpsPower method if we have gps data access.
     */
    public void startLogging() {
        sensorThread.setSensorLogging( true );
        sensorThread.setAudioPower( audioLogging );
        sensorThread.setGpsPower( gpsLogging );

        Intent messageIntent = new Intent( MainActivity.UI_ACTION_RECEIVER );
        messageIntent.putExtra( "serviceManagerRunning", true );
        sendBroadcast( messageIntent );

        logging = true;
        Log.i( logTag, "Logging Started." );
    }

    /**
     * Stop logging method:
     * 1. Unregister listeners for both sensors and battery.
     * 2. Turn gps recording off.
     * 3. Update main thread to initialize UI changes.
     */
    public void stopLogging() {
        sensorThread.setAudioPower( false );
        sensorThread.setGpsPower( false );
        sensorThread.setSensorLogging(false);

        Intent messageIntent = new Intent( MainActivity.UI_ACTION_RECEIVER );
        messageIntent.putExtra( "serviceManagerRunning", false );
        sendBroadcast( messageIntent );

        logging = false;
        Log.i(logTag, "Logging Stopped." );
    }

    @Override
    public void onDestroy () {
        stopLogging();
        uploadThread.stopUploadThread();
        Intent messageIntent = new Intent(MainActivity.UI_ACTION_RECEIVER);
        messageIntent.putExtra("serviceManagerRunning", false );
        sendBroadcast( messageIntent );
        logging = false;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind (Intent intent){
        return super.onUnbind(intent);
    }

    @Nullable
    @Override
    public IBinder onBind (Intent intent ){
        return new Binder();
    }


}


