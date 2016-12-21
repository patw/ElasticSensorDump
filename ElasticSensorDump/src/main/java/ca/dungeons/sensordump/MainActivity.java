package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    private static int MIN_SENSOR_REFRESH = 50;

    private boolean logging = false;

    private TextView tvProgress = null;
    private GPSLogger gpsLogger = new GPSLogger();
    private ElasticSearchIndexer esIndexer;

    // JSON structure for sensor and gps data
    private JSONObject joSensorData = new JSONObject();
    private SensorManager mSensorManager;
    private LocationManager locationManager;

    // Config data
    private SharedPreferences sharedPrefs;

    private int[] usableSensors;

    private boolean gpsBool = false;
    private boolean gpsChoosen = false;

    private long lastUpdate;
    private long startTime;

    private int sensorRefreshTime = 250;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //set initial view to main activity
        setContentView(R.layout.activity_main);

        // Prevent screen from going into landscape
        // get default preferences
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        // Get a list of all available sensors on the device and store in array
        enumerateSensors();

        //build a button to display current logging readings
        // Click a button, get some sensor data
        startButton();

        // build and set up on click functions to display the settings activity
        // Click a button, get the settings screen
        settingsButton();

        // build a Sliding bar and set up onSlide functions to adjust interval(tick) timing
        // Slide a bar to adjust the refresh times
        intervalSeekBar();

        // build a toggle button to display gps access
        // GPS Toggle
        gpsToggle();

    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // I don't really care about this yet.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {

        try {
            // Update timestamp in sensor data structure
            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ");
            String dateString = logDateFormat.format(logDate);
            joSensorData.put("@timestamp", dateString);

            // Store the logging start time with each document
            Date startDate = new Date(startTime);
            String startDateString = logDateFormat.format(startDate);
            joSensorData.put("start_time", startDateString);

            // Store the duration of the sensor log with each document
            long logDuration = (System.currentTimeMillis() - startTime) / 1000;
            joSensorData.put("log_duration_seconds", logDuration);

            // Dump gps data into document if it's ready
            if (gpsLogger.gpsHasData) {
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

            // Make sure we only generate docs at an adjustable rate
            // We'll use 250ms for now
            if (System.currentTimeMillis() > lastUpdate + sensorRefreshTime) {
                updateScreen();
                lastUpdate = System.currentTimeMillis();
                esIndexer.index(joSensorData);
            }
        } catch (Exception e) {
            Log.v("JSON Logging error", e.toString());
        }
    }
    // Go through the sensor array and light them all up
    private void startLogging() {
        // Prevent screen from sleeping if logging has started
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logging = true;
        startTime = System.currentTimeMillis();
        lastUpdate = startTime;
        gpsLogger.resetGPS();
        esIndexer = new ElasticSearchIndexer();
        esIndexer.updateURL(sharedPrefs);
        // Bind all sensors to activity
        for (int usableSensor : usableSensors) {
            mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(usableSensor), SensorManager.SENSOR_DELAY_NORMAL);
        }
        // Light up the GPS if we're allowed
        CheckGPS();
        updateScreen();
    }
    // Shut down the sensors by stopping listening to them
    private void stopLogging() {
        // Disable wakelock if logging has stopped
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logging = false;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText( getString(R.string.loggingStopped) );
        mSensorManager.unregisterListener(this);
        // Disable GPS
        gpsOFF();
        updateScreen();
    }
    // identify sensors and add to array
    private void enumerateSensors() {
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usableSensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
        }
    }
    // Update the display with readings/written/errors
    private void updateScreen() {
        ToggleButton toggleButton = (ToggleButton) findViewById(R.id.GPS_Toggle);

        String updateText = getString(R.string.Sensor_Readings) + esIndexer.indexRequests + "\n" +
            getString(R.string.Documents_Written) + esIndexer.indexSuccess + "\n" +
            getString(R.string.GPS_Updates) + gpsLogger.gpsUpdates + "\n" +
            getString(R.string.Errors) + esIndexer.failedIndex;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(updateText);

        if( gpsBool )
            toggleButton.setTextOn(getString(R.string.gpsOn));
        else
            toggleButton.setTextOff(getString(R.string.gpsOff));
        if( logging )
            toggleButton.setVisibility(View.GONE);
        else
            toggleButton.setVisibility(View.VISIBLE);
    }
    //main activity start logging button-- Main button
    public void startButton(){
        final Button btnStart = (Button) findViewById(R.id.btnStart);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logging) {
                    btnStart.setText(getString(R.string.buttonStop));

                    startLogging();
                    logging = true;
                } else {
                    btnStart.setText(getString(R.string.buttonStart));
                    stopLogging();
                    logging = false;
                }
            }
        });
    }
    // settings gear, top right of activity
    public void settingsButton(){
        final Intent settingsIntent = new Intent(this, SettingsActivity.class);
        final ImageButton ibSetup = (ImageButton) findViewById(R.id.ibSetup);
        ibSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(settingsIntent);
            }
        });
    }
    //main activity seek bar to change the tick interval
    public void intervalSeekBar(){
        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar2);
        final TextView tvSeekBarText = (TextView) findViewById(R.id.TickText);
        tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + seekBar.getProgress() + getString(R.string.milliseconds));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if(progress < MIN_SENSOR_REFRESH) progress = MIN_SENSOR_REFRESH;
                    tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + progress + getString(R.string.milliseconds));
                    sensorRefreshTime = progress;
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar){ } //intentionally blank
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { } //intentionally blank
        });
    }

    // gps Toggle Button, Main Activity
    public void gpsToggle(){
        final ToggleButton toggleButton = (ToggleButton) findViewById(R.id.GPS_Toggle);
        toggleButton.setTextOff(getString(R.string.gpsOff));

        toggleButton.setOnCheckedChangeListener( new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                gpsBool = isChecked;
                if( gpsBool )
                    toggleButton.setTextOn(getString(R.string.gpsOn));
                else
                    toggleButton.setTextOff(getString(R.string.gpsOff));

            }
        });
    }

    // GPS on/off persistent on app restart
    public void CheckGPS()
    {
        // if the user has already been asked about GPS access, do not ask again
        // else ask and verify access before listening
        if(!gpsChoosen) {
            if(sharedPrefs.getBoolean("GPS_bool", true) ) {
                gpsChoosen = true;
                gpsBool = true;
            } else {
                gpsChoosen = true;
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                gpsBool = ( ContextCompat.checkSelfPermission(this, android.Manifest.permission.
                        ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED );
            }
        }
        // Light up the GPS if we're allowed
        if ( gpsBool ) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
        }
    }
    // unbind GPS listener, should stop GPS thread in 2.0
    public void gpsOFF(){
        //unbind GPS listener if permission was granted
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.
                                    ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED )
            locationManager.removeUpdates(gpsLogger);
        else if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.
                ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)
        {}//intentionally blank, if permission is denied initially, gps was not on
        else //anything else
            Log.v("GPS Error", "GPS could not unbind");
    }


}
