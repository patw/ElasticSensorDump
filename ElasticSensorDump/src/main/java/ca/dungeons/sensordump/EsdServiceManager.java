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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class EsdServiceManager extends Service {

  /** String to identify this class in LogCat. */
  private static final String logTag = "EsdServiceManager";

  private SensorRunnable sensorRunnable;

  /**
   * This thread pool handles the timer in which we control this service.
   * Timer that controls if/when we should be uploading data to the server.
   */
  private final ScheduledExecutorService workingThreadPool = Executors.newScheduledThreadPool(4);

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
  /** Number of database rows. */
  public long databasePopulation = 0L;
  /** True if we are currently reading sensor data. */
  boolean logging = false;
  /** Toggle, if we should be recording AUDIO sensor data. */
  boolean audioLogging = false;
  /** Toggle, if we should be recording GPS data. */
  boolean gpsLogging = false;
  /** The rate in milliseconds we record sensor data. */
  int sensorRefreshRate = 250;
  /** Android connection manager. Use to find out if we are connected before doing any networking. */
  private ConnectivityManager connectionManager;

  private UploadRunnable uploadRunnable;

  private DatabaseHelper dbHelper;

  /** This is the runnable we will use to check network connectivity once every 30 min. */
  private final Runnable uploadTimerRunnable = new Runnable() {
    @Override
    public void run() {
      if( connectionManager.getActiveNetworkInfo().isConnected()&& !uploadRunnable.isWorking() ) {
        uploadRunnable.run();
      } else if ( uploadRunnable.isWorking()) {
        Log.e(logTag, "Uploading already in progress.");
      } else {
        Log.e(logTag, "Failed to submit uploads runnable to thread pool!");
      }
    }
  };

  /** Send a broadcast to the UI thread to update the parameters. */
  private final Runnable updateUiRunnable = new Runnable() {
    @Override
    public void run() {
      if ( getApplicationContext() != null ) {
        Log.e(logTag, "Shutting down service. Not logging!");
        updateDatabasePopulation();
        Intent outIntent = new Intent( MainActivity.UPDATE_UI_COUNTS );
        outIntent.putExtra("sensorReadings", sensorReadings);
        outIntent.putExtra("gpsReadings", gpsReadings);
        outIntent.putExtra("audioReadings", audioReadings);
        outIntent.putExtra( "documentsIndexed", documentsIndexed );
        outIntent.putExtra( "uploadErrors", uploadErrors );
        outIntent.putExtra( "databasePopulation", databasePopulation );
        getApplicationContext().sendBroadcast( outIntent );

        Log.e( logTag, sensorReadings + " : " + gpsReadings + " : " +
                audioReadings + " : " + documentsIndexed + " : " +
                uploadErrors + " : " + databasePopulation );
      }
    }
  };

  /**
   Service Timeout timer runnable.
   If we go more than a hour without recording any sensor data, shut down this thread.
   */
  private final Runnable serviceTimeoutRunnable = new Runnable() {
    @Override
    public void run() {
      if (!logging && !uploadRunnable.isWorking()) {
        Log.e(logTag, "Shutting down service. Not logging!");
        stopSelf();
      }
    }
  };

  /** Toggle, if this service is currently running. Used by the main activity. */
  private boolean serviceActive = false;



  /** Empty */
  EsdServiceManager(){


  }

  /**
   * Default constructor:
   * Instantiate the class broadcast receiver and messageFilters.
   * Register receiver to make sure we can communicate with the other threads.
   */
  @Override
  public void onCreate() {

  }

  /**
   * Runs when the mainActivity executes this service.
   * @param intent  - Not used.
   * @param flags   - Not used.
   * @param startId - Name of mainActivity.
   * @return START_STICKY will make sure the OS restarts this process if it has to trim memory.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    //Log.e(logTag, "ESD -- On Start Command." );
    if (!serviceActive) {
      SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
      dbHelper = new DatabaseHelper( this );

      /* Use SensorRunnable class to start the logging process. */
      sensorRunnable  = new SensorRunnable( this, sharedPrefs, dbHelper, this );
      workingThreadPool.submit( sensorRunnable );

      uploadRunnable = new UploadRunnable(sharedPrefs, dbHelper, this );
      connectionManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        /* Schedule periodic checks for internet connectivity. */
      setupTimers();
        /* Send a message to the main thread to indicate the manager service has been initialized. */
      serviceActive = true;
      Log.i(logTag, "Started service manager.");
    }
    // If the service is shut down, do not restart it automatically.
    return Service.START_NOT_STICKY;
  }

  /**
   * Start logging method:
   * Send toggle requests to the sensor thread receiver.
   * 1. SENSOR toggle.
   * 2. GPS toggle.
   * 3. AUDIO toggle.
   */
  public void startLogging() {
    logging = true;
    sensorListener.setSensorLogging(true);
    sensorListener.setGpsPower( gpsLogging );
    sensorListener.setAudioPower( audioLogging );
  }

  /**
   * Stop logging method:
   * 1. Unregister listeners for both sensors and battery.
   * 2. Turn gps recording off.
   * 3. Update main thread to initialize UI changes.
   */
  public void stopLogging() {
    logging = false;
    sensorListener.setSensorLogging( false );
  }

  /** Timer used to periodically check if the upload runnable needs to be executed. */
  private void setupTimers() {
    workingThreadPool.scheduleAtFixedRate(updateUiRunnable, 1000, 500, TimeUnit.MILLISECONDS);
    workingThreadPool.scheduleAtFixedRate(uploadTimerRunnable, 5, 30, TimeUnit.SECONDS);
    workingThreadPool.scheduleAtFixedRate(serviceTimeoutRunnable, 30, 30, TimeUnit.MINUTES);
  }

  void setGpsPower( boolean power ){
    if( sensorListener != null )
      sensorListener.setGpsPower( power );
  }

  void setAudioPower( boolean power ){
    if( sensorListener != null )
      sensorListener.setAudioPower(power);
  }

  void setRefreshRate( int rate ){
    sensorRefreshRate = rate;
    if( sensorListener != null )
      sensorListener.setSensorRefreshTime( sensorRefreshRate );
  }

  void updateDatabasePopulation(){
    databasePopulation = dbHelper.databaseEntries();
  }

  void indexSuccess( boolean success ){
    if (success) {
      documentsIndexed++;
    } else {
      uploadErrors++;
    }
  }

  void sensorSuccess( boolean phone, boolean gps, boolean audio ){
    if (logging && phone)
      sensorReadings++;

    if (gpsLogging && gps)
      gpsReadings++;

    if (audioLogging && audio)
      audioReadings++;
  }

  /**
   * This runs when the service either shuts itself down or the OS trims memory.
   * StopLogging() stops all sensor logging.
   * Unregister the Upload broadcast receiver.
   * Sends a message to the UI and UPLOAD receivers that we have shut down.
   */
  @Override
  public void onDestroy() {
    stopLogging();
    sensorListener.interrupt();
    super.onDestroy();
  }

  /** Not used as of yet. */
  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return new Binder();
  }


}


