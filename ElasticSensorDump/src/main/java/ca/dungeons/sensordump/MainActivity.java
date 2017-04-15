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
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 *
 */
public class MainActivity extends Activity implements SensorEventListener {

    /** True if we are currently reading sensor data. */
    private boolean logging = false;
    /** True if we are currently reading from gps sensors */
    boolean gpsLogging = false;
    /** Variable to track if we have permission to upload via metered connection.*/
    boolean mobileUpload = false;
    /** do not record more than once every 50 milliseconds. Default value is 250ms. */
    private static int MIN_SENSOR_REFRESH = 50;
    /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;

    /** Instance of sensor Manager. */
    private SensorManager mSensorManager;
    /** Instance of gps Manager. */
    private LocationManager locationManager;
    /** A new instance of the GPSLogger.java file.
     * Helper class to organize gps data. */
    private GPSLogger gpsLogger = new GPSLogger();
    /** A new instance of the DatabaseHelper class.
     * Database IO runs through this object. */
    public final DatabaseHelper dbHelper = new DatabaseHelper( this );
    /** Global SharedPreferences object. */
    static SharedPreferences sharedPrefs;
    /** Each loop, data wrapper to upload to Elastic. */
    private JSONObject joSensorData = new JSONObject();
    /** Array to hold sensor references. */
    private int[] usableSensors;
    /** True if we have permission to record gps data. */
    boolean gpsPermission = false;

    /** Timers, the schema is defined else where. */
    private long startTime, lastUpdate;

    /** Battery level in percentages. */
    double batteryLevel = 0;

    /** Number of sensor readings this session */
    public static long sensorReadings, documentsIndexed, gpsReadings,
                                                            uploadErrors, databaseEntries = 0;

    /** Listener for battery updates. */
    final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int tempLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int tempScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (tempLevel > 0 && tempScale > 0) {
                batteryLevel = tempLevel;
            }
        }
    };

    /**
     * Set contentView to portrait, and lock it that way.
     * Build main activity buttons.
     * Get a list of all available sensors on the device and store in array.
     *
     * @param savedInstanceState A generic object.
     */
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        sharedPrefs = this.getPreferences(Activity.MODE_PRIVATE);

        buildButtonLogic();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);

        usableSensors = new int[deviceSensors.size()];


        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
            Log.e("device ID", usableSensors[i] + ": Device ID. ");
        }

        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, batteryFilter);

    }

    /**
     * Required stub. Not used.
     * @param sensor Empty.
     * @param accuracy Empty.
     */
    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy){ }

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

        try {

            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ", Locale.US);
            String dateString = logDateFormat.format(logDate);
            joSensorData.put("@timestamp", dateString);

            Date startDate = new Date(startTime);
            String startDateString = logDateFormat.format(startDate);
            joSensorData.put("start_time", startDateString);

            long logDuration = (System.currentTimeMillis() - startTime) / 1000;
            joSensorData.put("log_duration_seconds", logDuration);

            if (gpsLogger.gpsHasData) {
                // Function to update the joSensorData list.
                joSensorData.put("location", "" + gpsLogger.gpsLat + "," + gpsLogger.gpsLong);
                joSensorData.put("start_location", "" + gpsLogger.gpsLatStart + "," + gpsLogger.gpsLongStart);
                joSensorData.put("altitude", gpsLogger.gpsAlt);
                joSensorData.put("accuracy", gpsLogger.gpsAccuracy);
                joSensorData.put("bearing", gpsLogger.gpsBearing);
                joSensorData.put("gps_provider", gpsLogger.gpsProvider);
                joSensorData.put("speed", gpsLogger.gpsSpeed);
                joSensorData.put("speed_kmh", gpsLogger.gpsSpeedKMH);
                joSensorData.put("speed_mph", gpsLogger.gpsSpeedMPH);
                joSensorData.put("gps_updates", gpsLogger.gpsUpdates);
                joSensorData.put("acceleration", gpsLogger.gpsAcceleration);
                joSensorData.put("acceleration_kmh", gpsLogger.gpsAccelerationKMH);
                joSensorData.put("acceleration_mph", gpsLogger.gpsAccelerationMPH);
                joSensorData.put("distance_metres", gpsLogger.gpsDistanceMetres);
                joSensorData.put("distance_feet", gpsLogger.gpsDistanceFeet);
                joSensorData.put("total_distance_metres", gpsLogger.gpsTotalDistance);
                joSensorData.put("total_distance_km", gpsLogger.gpsTotalDistanceKM);
                joSensorData.put("total_distance_miles", gpsLogger.gpsTotalDistanceMiles);
            }

            if (batteryLevel > 0)
                joSensorData.put("battery_percentage", batteryLevel);

            // Store sensor update into sensor data structure
            for (int i = 0; i < event.values.length; i++) {
                // We don't need the android.sensor. and motorola.sensor. stuff
                // Split it out and just get the sensor name
                String sensorName;
                String[] sensorHierarchyName = event.sensor.getStringType().split("\\.");
                if (sensorHierarchyName.length == 0) {
                    sensorName = event.sensor.getStringType();
                } else {
                    sensorName = sensorHierarchyName[sensorHierarchyName.length - 1] + i;
                }
                // Store the actual sensor data now unless it's returning NaN or something crazy big or small
                Float sensorValue = event.values[i];
                if (!sensorValue.isNaN() && sensorValue < Long.MAX_VALUE && sensorValue > Long.MIN_VALUE) {
                    joSensorData.put(sensorName, sensorValue);
                }
            }
            // Make sure we only generate docs at an adjustable rate.
            // 250ms is the default setting.
            if (System.currentTimeMillis() > lastUpdate + sensorRefreshTime) {
                updateScreen();
                // turn the gps Power true/false
                if(gpsLogging)
                    gpsPower(true);
                dbHelper.JsonToDatabase(joSensorData);
                sensorReadings++;
                lastUpdate = System.currentTimeMillis();
            }
        } catch (JSONException e) {
            Log.e("JSON Logging error", e.getMessage() + " space " + e.getCause() );
        }
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

        if (logging)
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
            if(!logging) {
                btnStart.setText(getString(R.string.buttonStop));
                startLogging();
            }else {
                btnStart.setText(getString(R.string.buttonStart));
                stopLogging();

            }
            }
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
                }else
                    sensorRefreshTime = progress * 10;
            tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + sensorRefreshTime + getString(R.string.milliseconds));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {} //intentionally blank

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {} //intentionally blank
        });

        final Switch mobileDataSwitch = (Switch) findViewById(R.id.mobileDataSwitch);
        mobileDataSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mobileUpload = isChecked;
            }
        });

        final Switch gpsDataSwitch = (Switch) findViewById(R.id.gpsDataSwitch);
        gpsDataSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                gpsPermission = isChecked;
                gpsPower(isChecked);
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
            gpsPermission = (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);

            BooleanToPrefs("GPS_Asked", true);
            BooleanToPrefs("GPS_Permission", gpsPermission );
        }
      return sharedPrefs.getBoolean("GPS_Permission", false);
    }

    /**
     * Power button for gps recording.
     * @param power True if we can light the gps listeners up.
     */
    public void gpsPower(boolean power) {
        boolean gpsAccess = gpsPermission();

        if ( power && gpsAccess && !gpsLogging ) {// Light up the GPS if we're allowed
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
                gpsLogging = true;
                return;
            } catch (SecurityException e) {
                Log.e("GPS ON", "GPS ON FAILED:: CHECK gpsPower()");
            }
        }

        if( !power && gpsAccess && gpsLogging ){ //unbind GPS listener if permission was granted && we are logging
            try {
                locationManager.removeUpdates(gpsLogger);
                gpsLogging = false;
                return;
            } catch (SecurityException e) {
                Log.e("ERROR: GPS receivers", "ERROR: GPS receivers are not running");
            }
        }
        Log.e("ERROR: GPS POWER", "ERROR: GPS power function");
    }

    /**
     * Iterate through the sensor list and power them up.
     */
    private void startLogging() {
         // Prevent screen from sleeping if logging has started
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        clearCounts();
         //check for gps access, store in preferences
        lastUpdate = startTime = System.currentTimeMillis();
        gpsLogger.resetGPS();

         // bind and record sensors to activity
        for (int usableSensor : usableSensors)
            mSensorManager.registerListener( this, mSensorManager.getDefaultSensor(usableSensor), SensorManager.SENSOR_DELAY_NORMAL);
         // make sure we start to listen to the battery receiver
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, batteryFilter);
        logging = true;
    }

    /**
     * Stop the recording of sensor data.
     */
    private void stopLogging() {
        // Disable wakelock if logging has stopped
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gpsPower(false);
        updateScreen();
        try {
            unregisterReceiver(batteryReceiver);
            mSensorManager.unregisterListener(this);
            logging = false;
        } catch (Exception e) {
            Log.e("Stop Logging", " Error stopLogging() " + e.getCause() + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try{
            unregisterReceiver(batteryReceiver);
            mSensorManager.unregisterListener(this);
            dbHelper.close();
        }catch(Exception e){
            Log.e("OnPause", "Error pausing app.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void clearCounts(){
        sensorReadings = documentsIndexed = gpsReadings = uploadErrors = databaseEntries = 0;
    }

}
