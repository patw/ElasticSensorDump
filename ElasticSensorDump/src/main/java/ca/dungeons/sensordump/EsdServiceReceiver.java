package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * Created by Gurtok on 9/19/2017.
 *
 */

public class EsdServiceReceiver extends BroadcastReceiver {

    /** ID this class in LogCat. */
    private static final String logTag = "EsdServiceReceiver";

    /** Instance of ESD service manager. */
    private EsdServiceManager esdServiceManager;

    /** Filter for the broadcast receiver. */
    IntentFilter messageFilter = new IntentFilter();

/* Sensor toggles. */
    /** Intent action address: Boolean - If we are recording PHONE sensor data. */
    public final static String SENSOR_MESSAGE = "esd.intent.action.message.SENSOR";

    /** Intent action address: Boolean - If we are recording GPS sensor data. */
    public final static String GPS_MESSAGE = "esd.intent.action.message.GPS";

    /** Intent action address: Boolean - If we are recording AUDIO sensor data. */
    public final static String AUDIO_MESSAGE = "esd.intent.action.message.AUDIO";

/* Interval rate change from main UI. */
    /**  Intent action address: integer - Rate change from user. */
    public final static String INTERVAL = "esd.intent.action.message.INTERVAL";

/* Update UI. */
    /** Intent action address: String - Call for the service to update the UI thread data records. */
    public final static String UPDATE_UI_display = "esd.intent.action.message.UPDATE_UI_display";

/* Update counts. */
    /** Intent action address: Boolean - Indicate if the current index attempt was successful. */
    public final static String INDEX_SUCCESS = "esd.intent.action.message.Uploads.INDEX_SUCCESS";

/* Sensor readings. */
    /** Intent action address: Boolean - Used by SensorListener to update EsdServiceManagers data. */
    public final static String SENSOR_SUCCESS = "esd.intent.action.message.SensorListener.SENSOR_SUCCESS";

    /** Default constructor:
     * This class is instantiated by the service manager, thus it passes itself to this class. */
    public EsdServiceReceiver( EsdServiceManager passedManagerObj ) {
        esdServiceManager = passedManagerObj;
        addFilters();
    }

    /** Assembles the message filter for this receiver. */
    private void addFilters(){
        messageFilter.addAction( SENSOR_MESSAGE );
        messageFilter.addAction( GPS_MESSAGE );
        messageFilter.addAction( AUDIO_MESSAGE );
        messageFilter.addAction( INTERVAL );

        messageFilter.addAction(UPDATE_UI_display);

        messageFilter.addAction( INDEX_SUCCESS );
        messageFilter.addAction( SENSOR_SUCCESS );
    }

    /** Main point of contact for the service manager.
     *  All information and requests are handled here.
     */
    @Override
        public void onReceive(Context context, Intent intent) {
            Intent messageIntent = new Intent();

            switch( intent.getAction() ){

                case (SENSOR_MESSAGE):
                    if (intent.getBooleanExtra("sensorPower", true)) {
                        esdServiceManager.startLogging();
                    } else {
                        esdServiceManager.stopLogging();
                    }
                    break;

                case GPS_MESSAGE:
                    esdServiceManager.gpsLogging = intent.getBooleanExtra("gpsPower", false);
                    messageIntent.setAction( SensorRunnable.GPS_POWER );
                    messageIntent.putExtra("gpsPower", esdServiceManager.gpsLogging);
                    esdServiceManager.sendBroadcast(messageIntent);
                    break;

                case AUDIO_MESSAGE:
                    esdServiceManager.audioLogging = intent.getBooleanExtra("audioPower", false);
                    messageIntent = new Intent(SensorRunnable.AUDIO_POWER);
                    messageIntent.putExtra("audioPower", esdServiceManager.audioLogging);
                    esdServiceManager.sendBroadcast(messageIntent);
                    break;

                case INTERVAL:
                    esdServiceManager.sensorRefreshRate = intent.getIntExtra("sensorInterval", 250);
                    messageIntent = new Intent(SensorRunnable.INTERVAL);
                    messageIntent.putExtra("sensorInterval", esdServiceManager.sensorRefreshRate);
                    esdServiceManager.sendBroadcast(messageIntent);
                    break;

                case UPDATE_UI_display:
                    esdServiceManager.updateUiData();
                    break;

                case INDEX_SUCCESS:
                    if( intent.getBooleanExtra(INDEX_SUCCESS, true) ){
                        esdServiceManager.documentsIndexed++;
                    }else{
                        esdServiceManager.uploadErrors++;
                    }
                    esdServiceManager.updateUiData();
                    break;

                case SENSOR_SUCCESS:
                    if( esdServiceManager.logging && intent.getBooleanExtra( "sensorReading", false) )
                        esdServiceManager.sensorReadings++;

                    if ( esdServiceManager.gpsLogging && intent.getBooleanExtra( "gpsReading", false) )
                        esdServiceManager.gpsReadings++;

                    if ( esdServiceManager.audioLogging && intent.getBooleanExtra( "audioReading", false) )
                        esdServiceManager.audioReadings++;
                    esdServiceManager.updateUiData();
                    break;

                default:
                    Log.e(logTag , "Received bad information from ACTION intent." );
                    break;
            }
        }



}

















