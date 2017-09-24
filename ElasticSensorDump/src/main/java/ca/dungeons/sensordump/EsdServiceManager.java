package ca.dungeons.sensordump;

import android.app.Service;

import android.content.Intent;
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

    /** String to identify this class in LogCat. */
    private static final String logTag = "EsdServiceManager";

    /** Android connection manager. Use to find out if we are connected before doing any networking. */
    private ConnectivityManager connectionManager;

    /** Uploads controls the data flow between the local database and Elastic server. */
    private Uploads_Receiver uploadsReceiver;

    /** Main activity preferences. Holds URL and name data. */
    private SharedPreferences sharedPrefs;

    /** True if we are currently reading sensor data. */
    boolean logging = false;

    /** Toggle, if we should be recording AUDIO sensor data. */
    boolean audioLogging = false;
    /** Toggle, if we should be recording GPS data.*/
    boolean gpsLogging = false;
    /** The rate in milliseconds we record sensor data. */
    int sensorRefreshRate = 250;
    /** Toggle, if this service is currently running. Used by the main activity. */
    private boolean serviceActive = false;
    /** Time of the last sensor recording. Used to shut down unused resources. */
    private long lastSuccessfulSensorResult;

    /** Number of sensor readings this session. */
    public int sensorReadings = 0;
    /** Number of audio readings this session. */
    public int audioReadings = 0;
    /** Number of gps locations recorded this session */
    public int gpsReadings = 0;
    /** Number of documents indexed to Elastic this session. */
    public int documentsIndexed = 0;
    /** Number of data uploaded failures this session. */
    public int uploadErrors = 0;

    /** This thread pool is the working pool. Use this to execute the sensor runnable and Uploads. */
    private ExecutorService workingThreadPool = Executors.newFixedThreadPool( 4 );

    /** This thread pool handles the timer in which we control this service.
     *  Timer that controls if/when we should be uploading data to the server.
     */
    private ScheduledExecutorService timerPool = Executors.newScheduledThreadPool( 2 );

    /** This is the runnable we will use to check network connectivity once every 30 min. */
    private Runnable uploadRunnable = new Runnable() {
        @Override
        public void run() {
            if( !uploadsReceiver.isWorking() && connectionManager.getActiveNetworkInfo().isConnected() ){
                //Log.e(logTag, "Submitting upload thread.");
                Intent uploadStartIntent = new Intent( Uploads_Receiver.START_UPLOAD_THREAD );
                sendBroadcast( uploadStartIntent );
            }else if( uploadsReceiver.isWorking() ){
                Log.e(logTag, "Uploading already in progress." );
            }else{
                Log.e(logTag, "Failed to submit uploads runnable to thread pool!" );
            }

        }
    };

    /**
     * Service Timeout timer runnable.
     * If we go more than an a half hour without recording any sensor data, shut down this thread.
     */
    private Runnable serviceTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // Last sensor result plus 1/2 hour in milliseconds is greater than the current time.
            boolean timeCheck = lastSuccessfulSensorResult + ( 1000*60*30 ) > System.currentTimeMillis();

            if( !logging && !uploadsReceiver.isWorking() && !timeCheck ){
                Log.e( logTag, "Shutting down service. Not logging!" );
                EsdServiceManager.super.stopSelf();
            }
        }
    };

    /**
     * Default constructor:
     * Instantiate the class broadcast receiver and messageFilters.
     * Register receiver to make sure we can communicate with the other threads.
     */
    @Override
    public void onCreate () {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences( this.getBaseContext() );

        EsdServiceReceiver esdMessageReceiver = new EsdServiceReceiver( this );
        registerReceiver( esdMessageReceiver, esdMessageReceiver.messageFilter );
    }

    /**
     * Runs when the mainActivity executes this service.
     * @param intent - Not used.
     * @param flags - Not used.
     * @param startId - Name of mainActivity.
     * @return START_STICKY will make sure the OS restarts this process if it has to trim memory.
     */
    @Override
    public int onStartCommand (Intent intent, int flags, int startId){
        //Log.e(logTag, "ESD -- On Start Command." );
        if( !serviceActive ){

            updateUiData();
            lastSuccessfulSensorResult = System.currentTimeMillis();
            connectionManager = (ConnectivityManager) getSystemService( CONNECTIVITY_SERVICE );

        /* Use SensorRunnable class to start the logging process. */
            SensorRunnable sensorRunnable  = new SensorRunnable( this, sharedPrefs );
            workingThreadPool.submit( sensorRunnable );

        /* Create an instance of Uploads, and submit to the thread pool to begin execution. */
            uploadsReceiver = new Uploads_Receiver( this, sharedPrefs, workingThreadPool );
            uploadsReceiver.registerMessageReceiver();

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
        return Service.START_STICKY;
    }

    /** This method uses the passed UI handler to relay messages if/when the activity is running. */
    synchronized void updateUiData(){

        if( getApplicationContext() != null ){

            Intent outIntent = new Intent( MainActivity.UI_DATA_RECEIVER );

            outIntent.putExtra( "sensorReadings", sensorReadings );
            outIntent.putExtra( "gpsReadings", gpsReadings );
            outIntent.putExtra( "audioReadings", audioReadings );

            outIntent.putExtra( "documentsIndexed", documentsIndexed );
            outIntent.putExtra( "uploadErrors", uploadErrors );

            sendBroadcast( outIntent );
        }
    }


    /** Timer used to periodically check if the upload runnable needs to be executed. */
    private void setupUploads() {
        timerPool.scheduleAtFixedRate( uploadRunnable, 10, 30, TimeUnit.SECONDS );
    } // Delay the task 10 seconds out and then repeat every 30 seconds.

    /** Timer used to periodically check if this service is being used (recording data). */
    void setupManagerTimeout(){
        timerPool.scheduleAtFixedRate( serviceTimeoutRunnable, 30, 30, TimeUnit.MINUTES );
    } // Delay the task 60 min out. Then repeat once every 60 min.

    /**
     * Start logging method:
     * Send toggle requests to the sensor thread receiver.
     * 1. SENSOR toggle.
     * 2. GPS toggle.
     * 3. AUDIO toggle.
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

    /**
     * This runs when the service either shuts itself down or the OS trims memory.
     * StopLogging() stops all sensor logging.
     * Unregister the Upload broadcast receiver.
     * Sends a message to the UI and UPLOAD receivers that we have shut down.
     */
    @Override
    public void onDestroy () {

        stopLogging();

        uploadsReceiver.unRegisterUploadReceiver();

        Intent messageIntent = new Intent( MainActivity.UI_ACTION_RECEIVER );
        messageIntent.putExtra( "serviceManagerRunning", false );
        sendBroadcast( messageIntent );

        messageIntent = new Intent( Uploads_Receiver.STOP_UPLOAD_THREAD );
        sendBroadcast( messageIntent );

        super.onDestroy();
    }

    /** Not used as of yet. */
    @Override
    public boolean onUnbind (Intent intent){
        return super.onUnbind(intent);
    }
    /** Not used as of yet. */
    @Nullable
    @Override
    public IBinder onBind (Intent intent ){
        return new Binder();
    }


}


