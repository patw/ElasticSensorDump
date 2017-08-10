package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import android.os.Bundle;
import android.util.Log;

/**
 * Elastic Sensor Dump.
 * Enumerates the sensors from an android device.
 * Record the sensor data and upload it to your elastic search server.
 */
public class MainActivity extends Activity{

    private final String logTag = "MainActivity";

    /** Global SharedPreferences object. */
    public static SharedPreferences sharedPrefs;

    public static final String UI_DATA_RECEIVER = "esd.intent.action.message.UI_DATA_RECEIVER";
    public static final String UI_ACTION_RECEIVER = "esd.intent.action.message.UI_ACTION_RECEIVER";

    /** We use this as a control to tell the service manager to stop if idle after 1 hour. */
    public static boolean serviceManagerRunning = false;

    /** Use this boolean value to determine if/when the activity is currently running. */
    public static boolean mainActivityRunning = false;



    /** Do NOT record more than once every 50 milliseconds. Default value is 250ms. */
    private final int MIN_SENSOR_REFRESH = 50;
    /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;

    /** Number of sensor readings this session */
    public int sensorReadings, documentsIndexed, gpsReadings, uploadErrors, audioReadings, databasePopulation = 0;


    private void createBroadcastReceiver(){
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction( UI_ACTION_RECEIVER );
        intentFilter.addAction( UI_DATA_RECEIVER );

        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive( Context context, Intent intent ) {

                if( intent.getAction().equals(UI_ACTION_RECEIVER ) ){
                    serviceManagerRunning = intent.getBooleanExtra("serviceManagerRunning", false );
                }

                if( intent.getAction().equals(UI_DATA_RECEIVER ) ){

                    String verb = intent.getStringExtra( "verb" );
                    if( verb.equals( "sensor" ) ){
                        //Log.e( logTag, "Received sensor data from service!" );
                        gpsReadings = intent.getIntExtra("gpsReadings", gpsReadings );
                        sensorReadings = intent.getIntExtra("sensorReadings", sensorReadings );
                        audioReadings = intent.getIntExtra( "audioReadings", audioReadings );
                        //Log.e( logTag, String.valueOf( sensorReadings ));
                    }else if( verb.equals( "upload" ) ){
                        documentsIndexed = intent.getIntExtra( "documentsIndexed", documentsIndexed );
                        uploadErrors = intent.getIntExtra( "uploadErrors", uploadErrors );
                        databasePopulation = intent.getIntExtra( "databasePopulation", databasePopulation );
                    }
                    updateScreen();
                }
            }
        };
        registerReceiver( broadcastReceiver, intentFilter );
    }


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
        buildButtonLogic();
        createBroadcastReceiver();
        mainActivityRunning = true;
        updateScreen();
        databasePopulation = new UploadTask(this, sharedPrefs ).getDatabasePopulation();
    }

    private void startServiceManager(){
        if( !serviceManagerRunning ){
            Intent startIntent =  new Intent( this, EsdServiceManager.class );
            startService( startIntent );
        }
    }

    /**
     * Update preferences with new permissions.
     *
     * @param asked      Preferences key.
     * @param permission True if we have access.
     */
    void BooleanToPrefs(String asked, boolean permission) {
        SharedPreferences.Editor sharedPref_Editor = sharedPrefs.edit();
        sharedPref_Editor.putBoolean(asked, permission);
        sharedPref_Editor.apply();
    }

      /**
       * Update the display with readings/written/errors.
       * Need to update UI based on the passed data intent.
       *
       */

    void updateScreen() {
        TextView sensorTV = (TextView) findViewById(R.id.sensor_tv);
        TextView documentsTV = (TextView) findViewById(R.id.documents_tv);
        TextView gpsTV = (TextView) findViewById(R.id.gps_TV);
        TextView errorsTV = (TextView) findViewById(R.id.errors_TV);
        TextView audioTV = (TextView) findViewById( R.id.audioCount );
        TextView databaseTV = (TextView) findViewById( R.id.databaseCount );

        sensorTV.setText( String.valueOf(sensorReadings) );
        documentsTV.setText( String.valueOf( documentsIndexed ) );
        gpsTV.setText( String.valueOf( gpsReadings ) );
        errorsTV.setText( String.valueOf( uploadErrors ) );
        audioTV.setText( String.valueOf( audioReadings ) );
        databaseTV.setText( String.valueOf( databasePopulation ) );

    }

      /**
       * Go through the sensor array and light them all up
       * btnStart: Click a button, get some sensor data.
       * ibSetup: Settings screen.
       * seekBar: Adjust the collection rate of data.
       * gpsToggle: Turn gps collection on/off.
       * audioToggle: Turn audio recording on/off.
       */
    void buildButtonLogic() {

        final ToggleButton startButton = (ToggleButton) findViewById(R.id.toggleStart);
        startButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged( CompoundButton buttonView, boolean isChecked ) {

                if( isChecked ){
                    Log.e( logTag, "Start button ON !");
                    startButton.setBackgroundResource( R.drawable.main_button_shape_on);
                }else{
                    Log.e( logTag, "Start button OFF !");
                    startButton.setBackgroundResource( R.drawable.main_button_shape_off);
                }
                // Broadcast to the service manager that we are toggling sensor logging.
                Intent messageIntent = new Intent();
                messageIntent.setAction( EsdServiceManager.SENSOR_MESSAGE );
                messageIntent.putExtra( "sensorPower", isChecked );
                sendBroadcast( messageIntent );
            }
        });

        final ImageButton settingsButton = (ImageButton) findViewById(R.id.settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            startActivity( new Intent(getBaseContext(), SettingsActivity.class) );
            }
        });


        final CheckBox gpsCheckBox = (CheckBox) findViewById(R.id.gpsCheckBox);

        gpsCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            // If gps button is turned ON.
                if( !gpsPermission() && isChecked ){
                    gpsCheckBox.toggle();
                    Toast.makeText( getApplicationContext(), "GPS access denied.", Toast.LENGTH_SHORT ).show();
                    BooleanToPrefs("gps_asked", false);
                }else{
                    // Broadcast to the service manager that we are toggling gps logging.
                    Intent messageIntent = new Intent( EsdServiceManager.GPS_MESSAGE );
                    messageIntent.putExtra("gpsPower", isChecked );
                    sendBroadcast( messageIntent );
                }

            }
        });

        final CheckBox audioCheckBox = (CheckBox) findViewById(R.id.audioCheckBox );
        audioCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                // If audio button is turned ON.
                if( !audioPermission() && isChecked ){
                    audioCheckBox.toggle();
                    Toast.makeText( getApplicationContext(), "Audio access denied.", Toast.LENGTH_SHORT ).show();
                    BooleanToPrefs("audio_Asked", false);
                }else{
                    // Broadcast to the service manager that we are toggling audio logging.
                    Intent messageIntent = new Intent( EsdServiceManager.AUDIO_MESSAGE );
                    messageIntent.putExtra( "audioPower", isChecked );
                    sendBroadcast( messageIntent );
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
                    seekBar.setProgress( MIN_SENSOR_REFRESH / 5 );
                    Toast.makeText( getApplicationContext(), "Minimum sensor refresh is 50 ms", Toast.LENGTH_SHORT).show();
                }else{
                    sensorRefreshTime = progress * 10;
                }

                Intent messageIntent = new Intent( EsdServiceManager.INTERVAL );
                messageIntent.putExtra( "sensorInterval", sensorRefreshTime );
                sendBroadcast( messageIntent );

                tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + sensorRefreshTime + getString(R.string.milliseconds));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {} //intentionally blank

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {} //intentionally blank
        });

    }

    /**
     * Prompt user for gps access.
     * Write this result to shared preferences.
     *
     * @return True if we asked for permission and it was granted.
     */
    public boolean gpsPermission() {

        boolean gpsPermissionFine = sharedPrefs.getBoolean("gps_permission_FINE", false );
        boolean gpsPermissionCoarse = sharedPrefs.getBoolean("gps_permission_COARSE", false );

        boolean asked = sharedPrefs.getBoolean("gps_asked", false);


        if( !gpsPermissionFine && !gpsPermissionCoarse && !asked ){

            String[] permissions = {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION};

            ActivityCompat.requestPermissions(this, permissions, 1);

            gpsPermissionCoarse = (ContextCompat.checkSelfPermission(this, Manifest.permission.
                    ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);

            gpsPermissionFine = (ContextCompat.checkSelfPermission(this, android.Manifest.permission.
                        ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);

            BooleanToPrefs("gps_asked", true);
            BooleanToPrefs("gps_permission_FINE", gpsPermissionFine);
            BooleanToPrefs("gps_permission_COARSE", gpsPermissionCoarse);


        }

        return ( gpsPermissionFine || gpsPermissionCoarse );
    }

    public boolean audioPermission(){
        boolean audioPermission = sharedPrefs.getBoolean( "audio_permission", false );

        if( sharedPrefs.getBoolean( "audio_Asked", false )){

            String[] permissions = { Manifest.permission.RECORD_AUDIO };

            ActivityCompat.requestPermissions(this, permissions, 1);


            audioPermission = (ContextCompat.checkSelfPermission(this, Manifest.permission.
                    RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);

            BooleanToPrefs("audio_Asked", true);
            BooleanToPrefs("audio_Permission", audioPermission);
        }

        return audioPermission;
    }





    /** If our activity is paused, we need to close out the resources in use. */
    @Override
    protected void onPause() {
        mainActivityRunning = false;
        super.onPause();
    }

    /** When the activity starts or resumes, we start the upload process immediately.
     *  If we were logging, we need to start the logging process.
     */
    @Override
    protected void onResume() {
        super.onResume();
        mainActivityRunning = true;
        startServiceManager();
        updateScreen();
    }





}
