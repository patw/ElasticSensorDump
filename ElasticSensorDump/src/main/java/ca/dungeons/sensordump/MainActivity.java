package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.SeekBar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    TextView tvProgress = null;
    GPSLogger gpsLogger = new GPSLogger();
    ElasticSearchIndexer esIndexer;
    // Hashmap stores all sensor and gps data
    private HashMap<String, Object> hmSensorData = new HashMap<String, Object>();
    private SensorManager mSensorManager;
    private LocationManager locationManager;

    private int[] usableSensors;
    private Handler refreshHandler;
    private boolean logging = false;

    private long lastUpdate = System.currentTimeMillis();
    private long startTime = System.currentTimeMillis();

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Wakelock to prevent app from sleeping
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Callback for settings screen
        final Intent settingsIntent = new Intent(this, SettingsActivity.class);

        // Load config data
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        // Spin up a new ES API indexer
        esIndexer = new ElasticSearchIndexer();
        esIndexer.updateURL(sharedPrefs);

        // Click a button, get some sensor data
        final Button btnStart = (Button) findViewById(R.id.btnStart);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logging) {
                    btnStart.setText(R.string.buttonStop);
                    esIndexer.updateURL(sharedPrefs);
                    startLogging();
                } else {
                    btnStart.setText(R.string.buttonStart);
                    stopLogging();
                }
            }
        });

        // Click a button, get the settings screen
        final ImageButton ibSetup = (ImageButton) findViewById(R.id.ibSetup);
        ibSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(settingsIntent);
            }
        });

        // Slide a bar to adjust the tick timing
        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        final TextView tvSeekBarText = (TextView) findViewById(R.id.TickText);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(fromUser)
                tvSeekBarText.setText( "Collection Interval :" + (progress + 100)
                                        + " (ms) ");//updates as user slides
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar){ } //intentionally blank
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { } //intentionally blank
        });

        // Get a list of all available sensors on the device and store in array
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usableSensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
        }

        // Refresh screen and store data periodically (make configurable!!)
        refreshHandler = new Handler();
        final Runnable r = new Runnable() {
            public void run() {
                if (logging) {
                    updateScreen();
                }
                refreshHandler.postDelayed(this, 1000);
            }
        };

        refreshHandler.postDelayed(r, 1000);

    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // I don't really care about this yet.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {

        // Update timestamp in sensor data structure
        Date logDate = new Date(System.currentTimeMillis());
        SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ");
        String dateString = logDateFormat.format(logDate);
        hmSensorData.put("@timestamp", dateString);

        // Store the logging start time with each document
        Date startDate = new Date(startTime);
        String startDateString = logDateFormat.format(startDate);
        hmSensorData.put("start_time", startDateString);

        // Store the duration of the sensor log with each document
        long logDuration = (System.currentTimeMillis() - startTime) / 1000;
        hmSensorData.put("log_duration_seconds", logDuration);

        // Dump gps data into document if it's ready
        if (gpsLogger.gpsHasData) {
            hmSensorData.put("location", "" + gpsLogger.gpsLat + "," + gpsLogger.gpsLong);
            hmSensorData.put("start_location", "" + gpsLogger.gpsLatStart + "," + gpsLogger.gpsLongStart);
            hmSensorData.put("altitude", gpsLogger.gpsAlt);
            hmSensorData.put("accuracy", gpsLogger.gpsAccuracy);
            hmSensorData.put("bearing", gpsLogger.gpsBearing);
            hmSensorData.put("gps_provider", gpsLogger.gpsProvider);
            hmSensorData.put("speed", gpsLogger.gpsSpeed);
            hmSensorData.put("speed_kmh", gpsLogger.gpsSpeedKMH);
            hmSensorData.put("speed_mph", gpsLogger.gpsSpeedMPH);
            hmSensorData.put("gps_updates", gpsLogger.gpsUpdates);
            hmSensorData.put("acceleration", gpsLogger.gpsAcceleration);
            hmSensorData.put("acceleration_kmh", gpsLogger.gpsAccelerationKMH);
            hmSensorData.put("acceleration_mph", gpsLogger.gpsAccelerationMPH);
            hmSensorData.put("distance_metres", gpsLogger.gpsDistanceMetres);
            hmSensorData.put("distance_feet", gpsLogger.gpsDistanceFeet);
            hmSensorData.put("total_distance_metres", gpsLogger.gpsTotalDistance);
            hmSensorData.put("total_distance_km", gpsLogger.gpsTotalDistanceKM);
            hmSensorData.put("total_distance_miles", gpsLogger.gpsTotalDistanceMiles);
        }

        // Store sensor update into sensor data structure
        for (int i = 0; i < event.values.length; i++) {

            // We don't need the android.sensor. and motorola.sensor. stuff
            // Split it out and just get the sensor name
            String sensorName = "";
            String[] sensorHierarchyName = event.sensor.getStringType().split("\\.");
            if (sensorHierarchyName.length == 0) {
                sensorName = event.sensor.getStringType();
            } else {
                sensorName = sensorHierarchyName[sensorHierarchyName.length - 1] + i;
            }

            // Store the actual sensor data now
            float sensorValue = event.values[i];
            hmSensorData.put(sensorName, sensorValue);
        }

        // Make sure we only generate docs at a reasonable rate (precusor to adjustable rates!)
        // We'll use 250ms for now
        if (System.currentTimeMillis() > lastUpdate + 250) {
            lastUpdate = System.currentTimeMillis();
            esIndexer.index(hmSensorData);
        }
    }

    // Go through the sensor array and light them all up
    private void startLogging() {
        esIndexer.resetCounters();

        for (int i = 0; i < usableSensors.length; i++) {
            mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(usableSensors[i]), SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Light up the GPS if we're allowed
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }

        logging = true;
    }

    // Shut down the sensors by stopping listening to them
    private void stopLogging() {
        logging = false;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(R.string.loggingStopped);
        mSensorManager.unregisterListener(this);

        // Disable GPS if we allowed it.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(gpsLogger);
        }
    }

    // Update the display with readings/written/errors
    public void updateScreen() {
        String updateText = "Sensor Readings: " + esIndexer.indexRequests + "\n" +
                "Documents Written: " + esIndexer.indexSuccess + "\n" +
                "GPS Updates: " + gpsLogger.gpsUpdates + "\n" +
                "Errors: " + esIndexer.failedIndex;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(updateText);
    }
}
