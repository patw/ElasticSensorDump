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
import android.widget.TextView;
import android.widget.ToggleButton;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    private static int MIN_SENSOR_REFRESH = 50;
    private ElasticSearchIndexer esIndexer;
    private SensorManager mSensorManager;
    private LocationManager locationManager;
    // Config data
    private SharedPreferences sharedPrefs;
    private GPSLogger gpsLogger = new GPSLogger();

    // JSON structure for sensor and gps data
    private JSONObject joSensorData = new JSONObject();

    private int[] usableSensors;
    private boolean logging = false;

    private long lastUpdate;
    private long startTime;

    private int sensorRefreshTime = 250;
    double batteryLevel = 0;

    final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //context.unregisterReceiver(this);
            int tempLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int tempScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if(tempLevel > 0 && tempScale > 0) {
                batteryLevel = tempLevel;
            }
        }
    };

    boolean gpsAccess = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Prevent screen from going into landscape
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        sharedPrefs = this.getPreferences(Activity.MODE_PRIVATE);
        // main activity buttons
        buildButtonLogic();
        // Get a list of all available sensors on the device and store in array
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usableSensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
        }
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, batteryFilter);
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
            // put battery status percentage into the Json.
            if (batteryLevel > 0) {
                joSensorData.put("battery_percentage", batteryLevel);
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
        //check for gps access, store in preferences
        startTime = System.currentTimeMillis();
        lastUpdate = startTime;
        gpsLogger.resetGPS();
        esIndexer = new ElasticSearchIndexer();
        esIndexer.updateURL(sharedPrefs);
        try {
            gpsPower("ON");
            // Bind all sensors to activity
            for (int usableSensor : usableSensors) {
                mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(usableSensor), SensorManager.SENSOR_DELAY_NORMAL);
            }
        }catch(Exception e){
            Log.v("Error starting a sensor", "Error starting a sensor!!");
        }

    }

    // Shut down the sensors by stopping listening to them
    private void stopLogging() {

        // Disable wakelock if logging has stopped
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            unregisterReceiver(batteryReceiver);
            mSensorManager.unregisterListener(this);
            gpsPower("OFF");
        }catch( Exception e){
            Log.v("Stop Logging", " Error stopLogging() ");
        }
    }

    // Update the display with readings/written/errors
    private void updateScreen() {
        // need to update UI based on the passed data intent
        //
        TextView mainBanner = (TextView) findViewById(R.id.main_Banner);
        TextView sensorTV = (TextView) findViewById(R.id.sensor_tv);
        TextView documentsTV = (TextView) findViewById(R.id.documents_tv);
        TextView gpsTV = (TextView) findViewById(R.id.gps_TV);
        TextView errorsTV = (TextView) findViewById(R.id.errors_TV);
        // update each metric
        sensorTV.setText(  (""+ esIndexer.indexRequests) );
        documentsTV.setText( (""+ esIndexer.indexSuccess) );
        gpsTV.setText(  (""+ gpsLogger.gpsUpdates) );
        errorsTV.setText(  (""+ esIndexer.failedIndex)  );
        if(logging)
            mainBanner.setText(getString(R.string.logging));
        else
            mainBanner.setText(getString(R.string.loggingStopped));
    }

    void buildButtonLogic() {
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
                // Callback for settings screen
                final Intent settingsIntent = new Intent(getBaseContext(), SettingsActivity.class);
                startActivity(settingsIntent);
            }
        });

        // Slide a bar to adjust the refresh times
        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        final TextView tvSeekBarText = (TextView) findViewById(R.id.TickText);
        tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + seekBar.getProgress() * 10 + getString(R.string.milliseconds));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (progress < MIN_SENSOR_REFRESH) progress = MIN_SENSOR_REFRESH;
                    tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + progress * 10 + getString(R.string.milliseconds));
                    sensorRefreshTime = progress * 10;
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            } //intentionally blank

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            } //intentionally blank
        });

        // GPS Toggle
        //
        final ToggleButton toggleButton = (ToggleButton) findViewById(R.id.GPS_Toggle);
        toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // if the toggle button changed to ON do...
                if (isChecked) {
                    // asks for permission if sharedPrefs does not contain a value for gpsChosen.
                    gpsAccess = gpsPermission();
                    gpsPower("ON");
                // if the toggle button is changed to OFF do...
                } else {
                    // if we have gps access turn off receivers
                    if( logging ) {
                        try {
                            gpsPower("OFF");
                        } catch (Exception e) {
                            Log.v("GPS_TOGGLE_PROBLEM", "GPS_TOGGLE_PROBLEM");
                        }
                    }
                }
            }
        });
    // end build button logic
    }
    // write the result of asking for GPS access
    void GpsToPrefs(String asked, boolean permission) {
        SharedPreferences.Editor sharedPref_Editor = sharedPrefs.edit();
        sharedPref_Editor.putBoolean(asked, permission);
        sharedPref_Editor.apply();
    }
    // ask user to grant gps access & write result to sharedPrefs
    void requestGpsPermission(){
        try{
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            gpsAccess = ( ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED );
            GpsToPrefs("GPS_Permission", gpsAccess);
            GpsToPrefs("GPS_ASKEDforPermission", true);
        }catch( SecurityException e) {
            Log.v("CHECK DPS", "Check GPS FAIL");
        }
    }
    // true = we have asked for permission, and it was granted
    boolean gpsPermission() {
        // if sharedPrefs does NOT contain a string for ASK for permission
        String TempGosChosen = sharedPrefs.getString("GPS_ASKEDforPermission",null);
        if( TempGosChosen == null ) {
            requestGpsPermission();
            sharedPrefs.getBoolean("GPS_ASKEDforPermission", true);
            return sharedPrefs.getBoolean("GPS_Permission", false);
        }
        return sharedPrefs.getBoolean("GPS_Permission", false);
    }
    // GPS on/off
    public void gpsPower(String power)
    {
        if ( power.equals("OFF") && gpsAccess ) {
            //unbind GPS listener if permission was granted && we are logging
            try {
                locationManager.removeUpdates(gpsLogger);
            }catch(SecurityException e){
                Log.e("ERROR: GPS receivers" ,"ERROR: GPS receivers are not running" );
            }
        }else if( power.equals("ON") && gpsAccess ){ // Light up the GPS if we're allowed
            try{
                locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
            }catch(SecurityException e){
                Log.v("GPS ON", "GPS ON FAILED:: CHECK gpsPower()");
            }
        }else{
            Log.v("gpsPower()", "Either not logging or we don't have permission");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
