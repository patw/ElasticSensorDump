package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;


import java.util.List;


/**
 *
 */
public class MainActivity extends Activity{

    /** Global SharedPreferences object. */
    static SharedPreferences sharedPrefs;

    /** Use SensorThread class to start the logging process.*/
    SensorThread sensorThread;

    /** Instance of sensor Manager. */
    private SensorManager mSensorManager;

    Handler handler;
    Thread sensorThread;
    HandlerThread sensorHandlerThread;

    /** Array to hold sensor references. */
    private int[] usableSensors;

    /** Default is no access.
     * If gps button is true this is true.*/
    boolean gpsPermission = false;

    /** True if we are currently reading sensor data. */
    public static boolean logging = false;

    /** do not record more than once every 50 milliseconds. Default value is 250ms. */
    private static final int MIN_SENSOR_REFRESH = 50;

    /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;

    /** Number of sensor readings this session */
    public static long sensorReadings, documentsIndexed, gpsReadings,
                                                            uploadErrors, databaseEntries = 0;

    /** Listener for battery updates. */
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int batteryData = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int batteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if ( batteryData > 0 && batteryScale > 0 ) {
                sensorThread.batteryLevel = batteryData;
            }
        }
    };

    /** Set up Handler */
    static Handler uiHandler = new Handler(new Handler.Callback(){
        @Override
        public boolean handleMessage(Message msg) {
            Bundle tempBundle = msg.getData();
            sensorReadings = tempBundle.getLong("sensorReadings");
            documentsIndexed = tempBundle.getLong("documentsIndexed");
            gpsReadings = tempBundle.getLong("gpsReadings");
            uploadErrors = tempBundle.getLong("uploadErrors");
            databaseEntries = tempBundle.getLong("databaseEntries");
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
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        sharedPrefs = this.getPreferences(Activity.MODE_PRIVATE);

        buildButtonLogic();
        sensorThread = new SensorThread(this, handler);
    }

    /**
     * Update the display with readings/written/errors.
     * Need to update UI based on the passed data intent.
     *
     */
    private void updateScreen() {

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
        dbEntries.setText(String.valueOf( databaseEntries ));

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

        final Button btnStart = (Button) findViewById(R.id.btnStart);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            if( !logging ){
                btnStart.setText(getString(R.string.buttonStop));
                logging = true;
                startLogging();
            }else {
                btnStart.setText(getString(R.string.buttonStart));
                logging = false;
                stopLogging();
            }}
        });

        final ImageButton ibSetup = (ImageButton) findViewById(R.id.ibSetup);
        ibSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final Intent settingsIntent = new Intent(getBaseContext(), SettingsActivity.class);
                startActivity(settingsIntent);
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
                    sensorThread.sensorRefreshTime = sensorRefreshTime;
                }

            tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + sensorRefreshTime + getString(R.string.milliseconds));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {} //intentionally blank

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {} //intentionally blank
        });

        // need to link this up with database
        final Switch mobileDataSwitch = (Switch) findViewById(R.id.mobileDataSwitch);
        mobileDataSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                //sensorThread.setMobileUpload( isChecked );
            }
        });

        final Switch gpsDataSwitch = (Switch) findViewById(R.id.gpsDataSwitch);
        gpsDataSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if( isChecked && gpsPermission() ){
                    sensorThread.gpsPower(true);
                }else{
                    gpsDataSwitch.toggle();
                }
            }
        });
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
    public boolean gpsPermission() {
        // if sharedPrefs does NOT contain a string for ASK for permission
        if ( ! sharedPrefs.contains("GPS_Asked") ) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            gpsPermission = (ContextCompat.checkSelfPermission(this, android.Manifest.permission.
                                        ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
            BooleanToPrefs("GPS_Asked", true);
            BooleanToPrefs("GPS_Permission", gpsPermission );
        }
      return sharedPrefs.getBoolean("GPS_Permission", false);
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
          // Clear UI count variables.
        sensorReadings = documentsIndexed = gpsReadings = uploadErrors = databaseEntries = 0;
          // Generate the sensor array.
        parseSensorList();

          // Create a new thread to record sensor data.
        if( !sensorThread.isAlive() ){

            sensorThread.setExitThread( false );
            sensorThread.start();
              // Register each sensor to this activity.
            for( int cursorInt: usableSensors){
                mSensorManager.registerListener(sensorThread, mSensorManager.getDefaultSensor(cursorInt),
                        SensorManager.SENSOR_DELAY_NORMAL, handler);
            }

              // Make sure we start to listen to the battery receiver
            IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver( batteryReceiver, batteryFilter, null, handler );

            sensorThread.gpsPower(true);

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

        try {
            unregisterReceiver(batteryReceiver);
            mSensorManager.unregisterListener(sensorThread);
        } catch (Exception e) {
            Log.e("Stop Logging", " Error stopLogging() " + e.getCause() + e.getMessage());
        }

        sensorThread.gpsPower( false );
        sensorThread.setExitThread( true );
        updateScreen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogging();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //startLogging();
    }

    /** Generate array with sensor IDs to reference. */
    void parseSensorList(){
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usableSensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
            Log.e("device ID", usableSensors[i] + ": Device ID. ");
        }
    }

}
