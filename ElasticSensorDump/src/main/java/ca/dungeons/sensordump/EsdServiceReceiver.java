package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;


public class EsdServiceReceiver extends BroadcastReceiver {

    public final static String SENSOR_MESSAGE = "esd.intent.action.message.SENSOR";
    public final static String GPS_MESSAGE = "esd.intent.action.message.GPS";
    public final static String AUDIO_MESSAGE = "esd.intent.action.message.AUDIO";
    public final static String IDLE_MESSAGE = "esd.intent.action.message.IDLE";
    public static String INTERVAL = "esd.intent.action.message.interval";

    @Override
    public void onReceive(Context context, Intent intent) {

        // Default idle message as a command to wait for input from UI.
        if( intent.getAction().equals( IDLE_MESSAGE )){

        }
        // Intent action to start recording phone sensors.
        if( intent.getAction().equals( SENSOR_MESSAGE )){

        }
        // Intent action to start gps recording.
        if( intent.getAction().equals( GPS_MESSAGE )){

        }
        // Intent action to start frequency recording.
        if( intent.getAction().equals( AUDIO_MESSAGE )){

        }
        // Receiver to adjust the sensor collection interval.
        if( intent.getAction().equals( INTERVAL )){
            intent.getIntExtra( "sensorInterval", 250 );

        }





    }





}
