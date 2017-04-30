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
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    private static int MIN_SENSOR_REFRESH = 50;

    private TextView tvProgress = null;
    private GPSLogger gpsLogger = new GPSLogger();
    private AudioLogger audioLogger = new AudioLogger();
    private ElasticSearchIndexer esIndexer;

    // JSON structure for sensor and gps data
    private JSONObject joSensorData = new JSONObject();
    private SensorManager mSensorManager;
    private LocationManager locationManager;

    // Config data
    private SharedPreferences sharedPrefs;

    private int[] usableSensors;
    private boolean logging = false;

    private long lastUpdate;
    private long startTime;

    private int sensorRefreshTime = 250;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        // Prevent screen from going into landscape
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        // Callback for settings screen
        final Intent settingsIntent = new Intent(this, SettingsActivity.class);
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        // Click a button, get some sensor data
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

        // Click a button, get the settings screen
        final ImageButton ibSetup = (ImageButton) findViewById(R.id.ibSetup);
        ibSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(settingsIntent);
            }
        });

        // Slide a bar to adjust the refresh times
        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
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

        // Get a list of all available sensors on the device and store in array
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usableSensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
        }
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

            // Dump audio data
            joSensorData.put("loudness", audioLogger.loudness);
            joSensorData.put("frequency", audioLogger.frequency);

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

        // Record audio if we're allowed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioLogger.startRecording();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        // Light up the GPS if we're allowed
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    // Shut down the sensors by stopping listening to them
    private void stopLogging() {

        // Disable wakelock if logging has stopped
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logging = false;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText( getString(R.string.loggingStopped) );
        mSensorManager.unregisterListener(this);
        audioLogger.stopRecording();

        // Disable GPS if we allowed it.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.removeUpdates(gpsLogger);
            } catch (Exception e) {
                Log.v("GPS Error", "GPS could not unbind");
            }
        }
    }

    // Update the display with readings/written/errors
    private void updateScreen() {

        String updateText = getString(R.string.Sensor_Readings) + esIndexer.indexRequests + "\n" +
            getString(R.string.Documents_Written) + esIndexer.indexSuccess + "\n" +
            getString(R.string.GPS_Updates) + gpsLogger.gpsUpdates + "\n" +
            getString(R.string.Errors) + esIndexer.failedIndex;

        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(updateText);

    }

    // Catch the permissions request for GPS being successful, and light up the GPS for this session
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case 1: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
                    }
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        audioLogger.startRecording();
                    }
                }
            }
        }
    }
}
