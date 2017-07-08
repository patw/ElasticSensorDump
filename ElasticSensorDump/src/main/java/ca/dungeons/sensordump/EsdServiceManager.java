package ca.dungeons.sensordump;


import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

public class EsdServiceManager extends Service {


    private UploadTask uploadTask;
    private SensorThread sensorThread;


    public class EsdServiceManagerReceiver extends BroadcastReceiver {


        @Override
        public void onReceive(Context context, Intent intent) {

            final String SENSOR_INTENT = "esd.intent.action.message.SENSOR";
            final String GPS_INTENT = "esd.intent.action.message.GPS";
            final String AUDIO_INTENT = "esd.intent.action.message.AUDIO";


            if( intent.getAction().equals( SENSOR_INTENT ) ){

            }

            if( intent.getAction().equals( GPS_INTENT ) ){

            }

            if( intent.getAction().equals( AUDIO_INTENT ) ){

            }

        }



    }


    public EsdServiceManager( ){

    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
