package ca.dungeons.sensordump;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    // Used to store sensor data before converting to JSON to submit
    public HashMap<String, Float> hmSensorData = new HashMap<String, Float>();
    public String json_sensor_data = null;
    TextView tvProgress = null;
    ArrayList<String> json_documents = new ArrayList<String>();
    private SensorManager mSensorManager;
    private int[] usable_sensors;
    private Handler refresh_handler;
    private int documents_sent = 0;
    private int documents_written = 0;
    private int sync_errors = 0;
    private boolean logging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /// Create a new data logger (make configurable!!)
        final ESDataLogger edl = new ESDataLogger("10.0.23.51", 9200, "sensor_dump", "nexus4");
        final Intent settings_intent = new Intent(this, SettingsActivity.class);

        // Click a button, get some sensor data
        final Button btnStart = (Button) findViewById(R.id.btnStart);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logging) {
                    btnStart.setText(R.string.button_stop);
                    startLogging();
                } else {
                    btnStart.setText(R.string.button_start);
                    stopLogging();
                }
            }
        });

        // Click a button, get the settings screen
        final ImageButton ibSetup = (ImageButton) findViewById(R.id.ibSetup);
        ibSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(settings_intent);
            }
        });

        // Get a list of all available sensors on the device and store in array
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usable_sensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usable_sensors[i] = deviceSensors.get(i).getType();
        }

        // Refresh screen and store data periodically (make configurable!!)
        refresh_handler = new Handler();
        final Runnable r = new Runnable() {
            public void run() {
                if (logging) {
                    updateScreen();
                    storeData(edl);
                }
                refresh_handler.postDelayed(this, 1000);
            }
        };

        refresh_handler.postDelayed(r, 1000);

    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // I don't really care about this
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        // Update timestamp in sensor data structure
        hmSensorData.put("Time", (float) System.currentTimeMillis());

        // Store sensor update into sensor data structure
        for (int i = 0; i < event.values.length; i++) {
            String sensor_name = event.sensor.getStringType().replace("android.sensor.", "") + i;
            float sensor_value = event.values[i];
            hmSensorData.put(sensor_name, sensor_value);
        }

        // Convert to JSON and add to log array
        JSONObject temp_json = new JSONObject(hmSensorData);
        json_sensor_data = temp_json.toString();
        json_documents.add(json_sensor_data);
    }

    // Go through the sensor array and light them all up
    private void startLogging() {
        for (int i = 0; i < usable_sensors.length; i++) {
            mSensorManager.registerListener(this,
                    mSensorManager.getDefaultSensor(usable_sensors[i]),
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
        logging = true;
    }

    // Shut down the sensors by stopping listening to them
    private void stopLogging() {
        logging = false;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(R.string.logging_stopped);
        mSensorManager.unregisterListener(this);
    }

    // Update the display with readings/written/errors
    public void updateScreen() {
        String update_text = "Sensor Readings: " + documents_sent + "\n" +
                "Documents Written: " + documents_written + "\n" +
                "Errors: " + sync_errors;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(update_text);
    }

    // Store the sensor data in the configured Elastic Search system
    private void storeData(ESDataLogger edl_instance) {
        if (json_documents.size() > 0) {
            edl_instance.store_hash(json_documents);
            documents_sent += json_documents.size();
            documents_written = edl_instance.documents_written;
            sync_errors = edl_instance.sync_errors;
        }
    }
}
