package ca.dungeons.sensordump;

import android.app.Service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class EsdServiceManager extends Service {

    private static final String logTag = "EsdServiceManager";

    private boolean serviceActive = false;

    private SharedPreferences sharedPrefs;

    private long lastSuccessfulSensorResult;

    private ConnectivityManager connectionManager;

    /** This thread pool is the working pool. Use this to execute the sensor runnable and Uploads. */
    private ExecutorService workingThreadPool = Executors.newFixedThreadPool( 2 );

    /** This thread pool handles the timer in which we control this service, as well as the
    * timer that controls if/when we should be uploading data to the server.
     */
    private ScheduledExecutorService timerPool = Executors.newScheduledThreadPool( 2 );

    /** Uploads controls the data flow between the local database and Elastic server. */
    private Uploads uploads;

    /** This is the runnable we will use to check network connectivity once every 30 min. */
    private Runnable uploadRunnable = new Runnable() {
        @Override
        public void run() {
            if( !uploads.isWorking() && connectionManager.getActiveNetworkInfo().isConnected() ){
                Log.e(logTag, "Submitting upload thread.");
                workingThreadPool.submit( uploads );
            }else{
                Log.e(logTag, "Failed to submit uploads runnable to thread pool!" );
            }

        }
    };

    private Runnable serviceTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // Last sensor result plus 1 hour in milliseconds is greater than the current time.
            boolean timeCheck = lastSuccessfulSensorResult + ( 1000*60*60 ) > System.currentTimeMillis();

            if( !logging && !uploads.isWorking() && !timeCheck ){
                Log.e( logTag, "Shutting down service. Not logging!" );
                EsdServiceManager.super.stopSelf();
            }
        }
    };


    /** True if we are currently reading sensor data. */
    public static boolean logging = false;
    private boolean audioLogging = false;
    private boolean gpsLogging = false;

    private int sensorRefreshRate = 250;

    /** These are the different actions that the receiver can manage. */
    public final static String SENSOR_MESSAGE = "esd.intent.action.message.SENSOR";
    public final static String GPS_MESSAGE = "esd.intent.action.message.GPS";
    public final static String AUDIO_MESSAGE = "esd.intent.action.message.AUDIO";
    public final static String INTERVAL = "esd.intent.action.message.INTERVAL";
    public final static String UPDATE_UI_UPLOAD_TASK = "esd.intent.action.message.UPDATE_UI_UPLOAD_TASK";
    public final static String UPDATE_UI_SENSOR_THREAD = "esd.intent.action.message.UPDATE_UI_SENSOR_THREAD";


    /** Number of sensor readings this session */
    public int sensorReadings, documentsIndexed, gpsReadings,
                uploadErrors, audioReadings, databasePopulation = 0;

    public EsdServiceManager(){
        // Intentionally blank.
    }

    @Override
    public void onCreate () {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences( this.getBaseContext() );

    }


    @Override
    public int onStartCommand (Intent intent,int flags, int startId){
        //Log.e(logTag, "ESD -- On Start Command." );
        if( !serviceActive){

            registerMessageReceiver();
            lastSuccessfulSensorResult = System.currentTimeMillis();
            connectionManager = (ConnectivityManager) getSystemService( CONNECTIVITY_SERVICE );

        /* Use SensorRunnable class to start the logging process. */
            SensorRunnable sensorRunnable  = new SensorRunnable( this, sharedPrefs );
            workingThreadPool.submit( sensorRunnable );

        /* Create an instance of Uploads, and submit to the thread pool to begin execution. */
            uploads = new Uploads( this, sharedPrefs );
            workingThreadPool.submit( uploadRunnable );

        /* Schedule periodic checks for internet connectivity. */
            setupUploads();
        /* Schedule periodic checks for service shutdown due to inactivity. */
            setupManagerTimeout();

        /* Send a message to the main thread to indicate the manager service has been initialized. */
            serviceActive = true;
            Intent messageIntent = new Intent(MainActivity.UI_ACTION_RECEIVER);
            messageIntent.putExtra("serviceManagerRunning", serviceActive );
            sendBroadcast( messageIntent );

            Log.i(logTag, "Started service manager.");
        }
        // If the service is shut down, do not restart it automatically.
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
                Intent messageIntent = new Intent();

                switch( intent.getAction() ){

                    case (SENSOR_MESSAGE):
                        if (intent.getBooleanExtra("sensorPower", true)) {
                            startLogging();
                        } else {
                            stopLogging();
                        }
                        break;

                    case GPS_MESSAGE:
                        gpsLogging = intent.getBooleanExtra("gpsPower", false);
                        messageIntent.setAction( SensorRunnable.GPS_POWER );
                        messageIntent.putExtra("gpsPower", gpsLogging);
                        sendBroadcast(messageIntent);
                        break;

                    case AUDIO_MESSAGE:
                        audioLogging = intent.getBooleanExtra("audioPower", false);
                        messageIntent = new Intent(SensorRunnable.AUDIO_POWER);
                        messageIntent.putExtra("audioPower", audioLogging);
                        sendBroadcast(messageIntent);
                        break;

                    case INTERVAL:
                        sensorRefreshRate = intent.getIntExtra("sensorInterval", 250);
                        messageIntent = new Intent(SensorRunnable.INTERVAL);
                        messageIntent.putExtra("sensorInterval", sensorRefreshRate);
                        sendBroadcast(messageIntent);
                        break;

                    case UPDATE_UI_SENSOR_THREAD:
                        sensorReadings = intent.getIntExtra("sensorReadings", sensorReadings);
                        gpsReadings = intent.getIntExtra("gpsReadings", gpsReadings);
                        audioReadings = intent.getIntExtra("audioReadings", audioReadings);
                        lastSuccessfulSensorResult = System.currentTimeMillis();
                        updateUiData("sensor");
                        break;

                    case UPDATE_UI_UPLOAD_TASK:
                        documentsIndexed = intent.getIntExtra("documentsIndexed", documentsIndexed);
                        uploadErrors = intent.getIntExtra("uploadErrors", uploadErrors);
                        databasePopulation = intent.getIntExtra("databasePopulation", databasePopulation);
                        updateUiData("upload");
                        break;

                    default:
                        Log.e(logTag , "Received bad information from ACTION intent." );
                        break;
                }
            }
        };
    // Register this broadcast receiver.
    this.registerReceiver( receiver, filter );
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
            }
            outIntent.putExtra( "verb", verb );
            sendBroadcast( outIntent );
        }
    }


    /** Timer used to periodically check if the upload task needs to be run. */
    private void setupUploads() {
        /* Use Upload Runnable. */
        timerPool.scheduleAtFixedRate( uploadRunnable, 30, 30, TimeUnit.SECONDS );
    } // Delay the task 5 seconds out and then repeat every 30 seconds.

    /** A timer to check if the service manager is running without logging. Check this once per hour.
     * When the service is in the background and logging, it will live past the activity life cycle.
     */
    void setupManagerTimeout(){
        /* Use serviceTimeout runnable. */
        timerPool.scheduleAtFixedRate( serviceTimeoutRunnable, 60, 60, TimeUnit.MINUTES );
    } // Delay the task 60 min out. Then repeat once every 60 min.

    /**
     * Start logging method:
     * 1. Bind sensor array to activity with a listener.
     * 2. Bind battery listener to activity.
     * 3. Clear out old data counts.
     * 4. Reset the gpsLogger counts.
     * 5. Send true to gpsPower method if we have gps data access.
     */
    public void startLogging() {
        logging = true;

        Intent messageIntent = new Intent( SensorRunnable.SENSOR_POWER );
        messageIntent.putExtra( "sensorPower", logging );
        sendBroadcast( messageIntent );

        if( gpsLogging ){
            messageIntent = new Intent( SensorRunnable.GPS_POWER );
            messageIntent.putExtra("gpsPower", gpsLogging );
            sendBroadcast( messageIntent );
        }

        if( audioLogging ){
            messageIntent = new Intent( SensorRunnable.AUDIO_POWER );
            messageIntent.putExtra("audioPower", audioLogging );
            sendBroadcast( messageIntent );
        }

    }

    /**
     * Stop logging method:
     * 1. Unregister listeners for both sensors and battery.
     * 2. Turn gps recording off.
     * 3. Update main thread to initialize UI changes.
     */
    public void stopLogging() {
        logging = false;
        Intent messageIntent = new Intent( SensorRunnable.SENSOR_POWER );
        messageIntent.putExtra( "sensorPower", logging );
        sendBroadcast( messageIntent );
    }

    @Override
    public void onDestroy () {

        stopLogging();

        Intent messageIntent = new Intent( MainActivity.UI_ACTION_RECEIVER );
        messageIntent.putExtra( "serviceManagerRunning", logging );
        sendBroadcast( messageIntent );

        messageIntent = new Intent( Uploads.STOP_UPLOAD_THREAD );
        sendBroadcast( messageIntent );

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


