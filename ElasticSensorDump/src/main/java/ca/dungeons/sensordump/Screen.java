package ca.dungeons.sensordump;

import android.os.Bundle;
import android.widget.TextView;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import ca.dungeons.sensordump.DataActivity;

/**
 * Created by Gurtok on 12/11/2016.
 */
/*
public class Screen {

    public long indexRequests = 0;
    public long indexSuccess = 0;
    public long failedIndex = 0;
    public int lastResponseCode;

    public class Screen(Bundle savedInstanceState) {




    }






    // Make sure we only generate docs at an adjustable rate
    // We'll use 250ms for now
    if (System.currentTimeMillis() > lastUpdate + defaultRefreshTime) {
        lastUpdate = System.currentTimeMillis();
        esIndexer.index(joSensorData);
        updateScreen();
        // Update the display with readings/written/errors


    public void updateScreen() {

        String updateText = getString(R.string.Sensor_Readings) + esIndexer.indexRequests + "\n" +
                getString(R.string.Documents_Written) + esIndexer.indexSuccess + "\n" +
                getString(R.string.GPS_Updates) + gpsLogger.gpsUpdates + "\n" +
                getString(R.string.Errors) + esIndexer.failedIndex;

        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(updateText);

    }


}
*/