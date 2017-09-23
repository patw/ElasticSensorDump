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

    private static final String logTag = "EsdServiceReceiver";

    private EsdServiceManager esdServiceManager;

    IntentFilter messageFilter = new IntentFilter();

    /* Communication with ElasticSearchIndexer. */
    /** These are the different actions that the receiver can manage. */
    public final static String SENSOR_MESSAGE = "esd.intent.action.message.SENSOR";
    public final static String GPS_MESSAGE = "esd.intent.action.message.GPS";
    public final static String AUDIO_MESSAGE = "esd.intent.action.message.AUDIO";
    public final static String INTERVAL = "esd.intent.action.message.INTERVAL";
/* Upload UI. */
    /** Use these receivers to update the UI thread when possible. */
    public final static String UPDATE_UI_UPLOAD_TASK = "esd.intent.action.message.UPDATE_UI_UPLOAD_TASK";
    public final static String UPDATE_UI_SENSOR_THREAD = "esd.intent.action.message.UPDATE_UI_SENSOR_THREAD";

/* Update counts. */
    /** Used by ElasticSearchIndexer to indicate if the current upload attempt was successful or failed. */
    public final static String INDEX_SUCCESS = "esd.intent.action.message.Uploads.INDEX_SUCCESS";

/* Sensor readings. */
    /** Used by SensorListener to update EsdServiceManagers' variables. */
    public final static String SENSOR_SUCCESS = "esd.intent.action.message.SensorListener.SENSOR_SUCCESS";


    public EsdServiceReceiver( EsdServiceManager passedManagerObj ) {

        esdServiceManager = passedManagerObj;
        addFilters();


    }

    private void addFilters(){
        messageFilter.addAction( SENSOR_MESSAGE );
        messageFilter.addAction( GPS_MESSAGE );
        messageFilter.addAction( AUDIO_MESSAGE );
        messageFilter.addAction( INTERVAL );

        messageFilter.addAction( UPDATE_UI_SENSOR_THREAD );
        messageFilter.addAction( UPDATE_UI_UPLOAD_TASK );

        messageFilter.addAction( INDEX_SUCCESS );
        messageFilter.addAction( SENSOR_SUCCESS );
    }

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

                case UPDATE_UI_SENSOR_THREAD:
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

















