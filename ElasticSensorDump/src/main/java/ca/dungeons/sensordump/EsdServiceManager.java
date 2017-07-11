package ca.dungeons.sensordump;


import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

import static ca.dungeons.sensordump.MainActivity.sharedPrefs;

public class EsdServiceManager extends Service {


    private UploadTask uploadTask;
    private SensorThread sensorThread;
    /** True if we are currently reading sensor data. */
    public static boolean logging = false;
    /** True if user gave permission to log GPS data. */
    private static boolean gpsLogging = false;
    /** True if user gave permission to log AUDIO data. */
    private static boolean audioLogging = false;
    /** Use SensorThread class to start the logging process. */
    SensorThread sensorThread;
    /** UploadTask controls the data flow between the local database and Elastic server. */
    UploadTask uploadTask;
    ConnectivityManager connectivityManager;

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
                documentsIndexed = msg.arg1;
                uploadErrors = msg.arg2;
            }

            updateScreen();
            return false;
        }
    });


    public EsdServiceManager( ){

    }

    void setupUploadTimer() {
        Timer uploadTimer = new Timer();
        uploadTimer.schedule(new TimerTask() {
            public void run() {
                startUpload();
            }
        }, 500, 30000);
    } // Delay the task 5 seconds out and then repeat every 30 seconds.


    @Override
    public void onCreate () {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        uploadTask = new UploadTask(getApplicationContext(), sharedPrefs);
    }

    @Override
    public int onStartCommand (Intent intent,int flags, int startId){
        return super.onStartCommand(intent, flags, startId);
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

        sensorThread = new SensorThread(getApplicationContext(), uiHandler);
        sensorThread.start();

        if (sensorThread.isAlive()) {
            logging = true;
            Log.i("MainAct-startLogging", "Logging Started. ");
        } else {
            Log.e("MainAct-startLogging", sensorThread.getState() + "");
        }
    }

    /**
     * Stop logging method:
     * 1. Unregister listeners for both sensors and battery.
     * 2. Turn gps recording off.
     * 3. Update main thread to initialize UI changes.
     */
    private void stopLogging() {
        if (logging) {
            // Disable wakelock if logging has stopped
            sensorThread.stopSensorThread();
            if (!sensorThread.isAlive()) {
                logging = false;
                Log.i("MainAct-stopLogging", "Logging Stopped. ");
                // Need to figure out a way to get the logging status back to the UI thread.
                // But what if the UI is not active? A broadcast would be lost to the nether.
                // So we have to write to permanent storage, or wait til the UI is active again to send an update message.
            } else {
                Log.e("MainAct-stopLogging", "Failed to shut down sensor thread.");
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
    private void startUpload() {
        if (uploadTask == null || !uploadTask.isAlive()) {
            uploadTask = new UploadTask(this, uiHandler, sharedPrefs);
            uploadTask.start();
        }
    }

    /**
     * Stop upload async task.
     * Verify that the task is running.
     * Cancel the task.
     */
    private void stopUpload() {
        if (uploadTask != null && uploadTask.isAlive())
            uploadTask.stopSensorThread();
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
     * Prompt user for gps access.
     * Write this result to shared preferences.
     *
     * @return True if we asked for permission and it was granted.
     */
    public boolean gpsPermission() {

        boolean gpsPermissionFine = false;


        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};

        ActivityCompat.requestPermissions(this, permissions, 1);

        boolean gpsPermissionCoarse = (ContextCompat.checkSelfPermission(this, Manifest.permission.
                ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);


        if (!gpsPermissionCoarse) {
            gpsPermissionFine = (ContextCompat.checkSelfPermission(this, android.Manifest.permission.
                    ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
        }
        BooleanToPrefs("GPS_Asked", true);
        BooleanToPrefs("GPS_PermissionFine", gpsPermissionFine);
        BooleanToPrefs("GPS_PermissionCoarse", gpsPermissionCoarse);

        return (gpsPermissionFine || gpsPermissionCoarse);
    }

    @Override
    public void onDestroy () {
        super.onDestroy();
    }

    @Override
    public boolean onUnbind (Intent intent){
        return super.onUnbind(intent);
    }

    @Nullable
    @Override
    public IBinder onBind (Intent intent ){
        return null;
    }


}


