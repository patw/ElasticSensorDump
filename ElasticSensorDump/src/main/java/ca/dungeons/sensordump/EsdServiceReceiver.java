package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


public class EsdServiceReceiver extends BroadcastReceiver {

    final static String SENSOR_INTENT = "esd.intent.action.message.SENSOR";
    final static String GPS_INTENT = "esd.intent.action.message.GPS";
    final static String AUDIO_INTENT = "esd.intent.action.message.AUDIO";
    final static String IDLE_SERVICE = "esd.intent.action.message.IDLE";

    @Override
    public void onReceive(Context context, Intent intent) {

        // Default idle message as a command to wait for input from UI.
        if( intent.getAction().equals( IDLE_SERVICE )){

        }
        // Intent action to start recording phone sensors.
        if( intent.getAction().equals( SENSOR_INTENT ) ){

        }
        // Intent action to start gps recording.
        if( intent.getAction().equals( GPS_INTENT ) ){

        }
        // Intent action to start frequency recording.
        if( intent.getAction().equals( AUDIO_INTENT ) ){

        }



    }





}
