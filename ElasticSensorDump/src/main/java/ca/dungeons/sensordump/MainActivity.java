package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;


/**
 *
 */
public class MainActivity extends Activity{

        /** Global SharedPreferences object. */
    public static SharedPreferences sharedPrefs;
        /** Use SensorThread class to start the logging process. */
    SensorThread sensorTask;
        /** UploadTask controls the data flow between the local database and Elastic server. */
    UploadTask uploadTask;
        /** Used to determine if we are allowed to upload via Mobile Data. */
    ConnectivityManager connectivityManager;
        /** Allows us to keep track of our current, if existing, UPLOAD async task. */
    private AsyncTask.Status uploadTaskStatus;
        /** True if we are currently reading sensor data. */
    public static boolean logging = false;
        /** True if user gave permission to log GPS data. */
    private static boolean gpsLogging = false;
        /** do not record more than once every 50 milliseconds. Default value is 250ms. */
    private static final int MIN_SENSOR_REFRESH = 50;
        /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;
        /** Number of sensor readings this session */
    public static long sensorReadings, documentsIndexed, gpsReadings, uploadErrors, databaseEntries = 0;
    private long screenRefreshTimer = System.currentTimeMillis();

        /** Set up Handler */
    Handler uiHandler = new Handler(new Handler.Callback(){
        @Override
        public boolean handleMessage(Message msg) {
        // Sensor Readings. Arg1 = sensor updates. Arg2 = gpsUpdates.
        if( msg.what == SensorThread.SENSOR_THREAD_ID ){
            sensorReadings = msg.arg1;
            gpsReadings = msg.arg2;
        }
        // Upload task variables.
        if( msg.what == UploadTask.UPLOAD_TASK_ID){
            Bundle tempBundle = msg.getData();
            // Population of database.
            databaseEntries = tempBundle.getLong("databaseEntries");
            documentsIndexed = tempBundle.getLong("documentsIndexed");
            uploadErrors = tempBundle.getLong("uploadErrors");
        }
        updateScreen();
        return false;
        }
    });

      /**
       * Set contentView to portrait, and lock it that way.
       * Build main activity buttons.
       * Get a list of all available sensors on the device and store in array.
       * @param savedInstanceState A generic object.
       */
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate( savedInstanceState);
        setContentView( R.layout.activity_main);
        setRequestedOrientation( ActivityInfo.SCREEN_ORIENTATION_LOCKED );
    }

      /**
       * Update the display with readings/written/errors.
       * Need to update UI based on the passed data intent.
       *
       */
    void updateScreen() {

        TextView mainBanner = (TextView) findViewById(R.id.main_Banner);
        TextView sensorTV = (TextView) findViewById(R.id.sensor_tv);
        TextView documentsTV = (TextView) findViewById(R.id.documents_tv);
        TextView gpsTV = (TextView) findViewById(R.id.gps_TV);
        TextView errorsTV = (TextView) findViewById(R.id.errors_TV);
        TextView dbEntries = (TextView) findViewById(R.id.databaseCount);

        sensorTV.setText( String.valueOf(sensorReadings) );
        documentsTV.setText( String.valueOf( documentsIndexed ) );
        gpsTV.setText( String.valueOf( gpsReadings ) );
        errorsTV.setText( String.valueOf( uploadErrors ) );
        if( System.currentTimeMillis() > screenRefreshTimer + 500 ){
            dbEntries.setText( String.valueOf( getDatabasePopulation()) );
            screenRefreshTimer = System.currentTimeMillis();
        }


        if ( logging )
            mainBanner.setText(getString(R.string.logging));
        else
            mainBanner.setText(getString(R.string.loggingStopped));


    }

      /**
       * Go through the sensor array and light them all up
       * btnStart: Click a button, get some sensor data.
       * ibSetup: Settings screen.
       * seekBar: Adjust the collection rate of data.
       * gpsToggle: Turn gps collection or/off.
       */
    void buildButtonLogic() {

        final ToggleButton startButton = (ToggleButton) findViewById(R.id.toggleStart);
        startButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged( CompoundButton buttonView, boolean isChecked ) {
                if( isChecked ){
                    Log.e("MainActivity", "Start button ON !");
                    startLogging();
                }else{
                    Log.e("MainActivity", "Start button OFF !");
                    stopLogging();
                }
            }
        });

        final ImageButton settingsButton = (ImageButton) findViewById(R.id.settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            final Intent settingsIntent = new Intent(getBaseContext(), SettingsActivity.class);
            startActivity(settingsIntent);
            }
        });


        final ToggleButton gpsToggle = (ToggleButton) findViewById(R.id.toggleGPS);
        gpsToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                if( isChecked ){
                    if( gpsPermission( true ) ){
                        gpsLogging = true;
                        stopLogging();
                        startLogging();
                    }else{
                        gpsLogging = false;
                        Toast.makeText( getApplicationContext(), "Failed to get access to GPS sensors.", Toast.LENGTH_SHORT ).show();
                    }
                }else{
                    if( gpsLogging ){
                        gpsLogging = false;
                        stopLogging();
                        startLogging();
                    }
                }
            }
        });

        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        final TextView tvSeekBarText = (TextView) findViewById(R.id.TickText);
        tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + seekBar.getProgress() * 10 + getString(R.string.milliseconds));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if ( progress * 10 < MIN_SENSOR_REFRESH ) {
                    seekBar.setProgress( MIN_SENSOR_REFRESH / 10 );
                    Toast.makeText(getApplicationContext(),"Minimum sensor refresh is 50 ms",Toast.LENGTH_SHORT).show();
                }else{
                    sensorRefreshTime = progress * 10;
                    sensorTask.setSensorRefreshTime(sensorRefreshTime);
                }
                tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + sensorRefreshTime + getString(R.string.milliseconds));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {} //intentionally blank

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {} //intentionally blank
        });

    }

      /**
       * Start logging method:
       * 1. Bind sensor array to activity with a listener.
       * 2. Bind battery listener to activity.
       * 3. Clear out old data counts.
       * 4. Reset the gpsLogger counts.
       * 5. Send true to gpsPower method if we have gps data access.
       */
    private void startLogging() {
          // Prevent screen from sleeping if logging has started
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Create a new Async task to record sensor data.
        sensorTask = new SensorThread( this, uiHandler );
        AsyncTask.Status sensorTaskStatus = sensorTask.getStatus();
        sensorReadings = documentsIndexed = gpsReadings = uploadErrors = 0;
        logging = true;

        if( sensorTaskStatus == AsyncTask.Status.PENDING ){
            sensorTask.execute( gpsLogging );
            Log.e("MainActivity", "Thread executed" );
        }
    }

      /**
       * Stop logging method:
       * 1. Unregister listeners for both sensors and battery.
       * 2. Turn gps recording off.
       * 3. Update main thread to initialize UI changes.
       */
    private void stopLogging() {
          // Disable wakelock if logging has stopped
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if( logging ){
            logging = false;
            sensorTask.cancel( true );
            if( sensorTask.getStatus() == AsyncTask.Status.FINISHED ){
                sensorTask = null;
            }else{
                Log.e("MainActivity", "Error shutting down sensorTask." + sensorTask.getStatus().toString() );
            }
        updateScreen();
        }
    }

    /**
     * Start Upload async task:
     * New async task for uploading data to server.
     * Make sure we are connected to the net before starting the task.
     * Check our upload task status to make sure the process is pending.
     * If both our task is pending, and we have internet connectivity, execute the task.
     */
    private void startUpload(){

        uploadTask = new UploadTask( getApplicationContext(), uiHandler, sharedPrefs );
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

        uploadTaskStatus = uploadTask.getStatus();
        boolean taskStatusPending = ( uploadTaskStatus == AsyncTask.Status.PENDING );
        boolean connectedToData = ( networkInfo.getState() == NetworkInfo.State.CONNECTED );

        if( taskStatusPending && connectedToData ) {
            uploadTask.execute();
        }
    }

    /**
     * Stop upload async task.
     * Verify that the task is running.
     * Cancel the task.
     */
    private void stopUpload(){
        uploadTaskStatus = uploadTask.getStatus();
        if( uploadTaskStatus == AsyncTask.Status.RUNNING ){
            uploadTask.cancel( true );
            uploadTask = null;
        }
    }

    /** If our activity is paused, we need to close out the resources in use. */
    @Override
    protected void onPause() {
        super.onPause();
        stopUpload();
        if( logging ){
            stopLogging();
        }
    }

    /** When the activity starts or resumes, we start the upload process immediately.
     *  If we were logging, we need to start the logging process.
     */
    @Override
    protected void onResume() {
        super.onResume();
        buildButtonLogic();

        getDatabasePopulation();

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences( getBaseContext() );
        connectivityManager = ( ConnectivityManager ) getSystemService( Context.CONNECTIVITY_SERVICE );

        updateScreen();
        startUpload();

    }

    private long getDatabasePopulation(){
        DatabaseHelper databaseHelper = new DatabaseHelper( this );
        databaseEntries = databaseHelper.databaseEntries();
        databaseHelper.close();
        return databaseEntries;
    }

    /**
     * Update preferences with new permissions.
     * @param asked Preferences key.
     * @param permission True if we have access.
     */
    void BooleanToPrefs(String asked, boolean permission) {
        SharedPreferences.Editor sharedPref_Editor = sharedPrefs.edit();
        sharedPref_Editor.putBoolean(asked, permission);
        sharedPref_Editor.apply();
    }

    /**
     * Prompt user for gps access.
     * Write this result to shared preferences.
     * @return True if we asked for permission and it was granted.
     */
    public boolean gpsPermission( boolean askAgain ){

        // if sharedPrefs does NOT contain a string for ASK for permission
        if ( ! sharedPrefs.contains("GPS_Asked") || askAgain ) {
            ActivityCompat.requestPermissions( this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            boolean gpsPermission = (ContextCompat.checkSelfPermission( this, android.Manifest.permission.
                    ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
            BooleanToPrefs("GPS_Asked", true);
            BooleanToPrefs("GPS_Permission", gpsPermission  );
        }
        return sharedPrefs.getBoolean("GPS_Permission", false);
    }


}
