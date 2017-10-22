package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.provider.ContactsContract;

/**
 * Listener class to record sensorMessageHandler data.
 * @author Gurtok.
 * @version First version of sensor thread.
 */
class SensorRunnable implements Runnable {

  /**
   * Main activity context.
   */
  private final Context passedContext;

  /** */
  private final SensorListener sensorListener;

  /** */
  final static String SENSOR_POWER = "esd.serviceManager.message.SENSOR_POWER";
  /** */
  final static String GPS_POWER = "esd.serviceManager.message.GPS_POWER";

  /** */
  final static String AUDIO_POWER = "esd.serviceManager.message.AUDIO_POWER";

  /** */
  final static String INTERVAL = "esd.serviceManager.message.sensor.REFRESH_RATE";

// Guts.

  /**
   * Constructor:
   * Initialize the sensorMessageHandler manager.
   */
  SensorRunnable(Context context, SharedPreferences sharedPreferences, DatabaseHelper dbHelper, EsdServiceManager serviceManager ) {
    passedContext = context;
    sensorListener = new SensorListener(passedContext, sharedPreferences, dbHelper, serviceManager);
  }

  /** */
  @Override
  public void run() {
    registerMessageReceiver();
  }

  /** */
  private void registerMessageReceiver() {

    IntentFilter filter = new IntentFilter();
    filter.addAction(SENSOR_POWER);
    filter.addAction(GPS_POWER);
    filter.addAction(AUDIO_POWER);
    filter.addAction(INTERVAL);

    BroadcastReceiver receiver = new BroadcastReceiver() {

      @Override
      public void onReceive(Context context, Intent intent) {

        // Intent action to start recording phone sensors.
        if (intent.getAction().equals(SENSOR_POWER)) {
          sensorListener.setSensorLogging( intent.getBooleanExtra("sensorPower", true ) );
        }

        // Intent action to start gps recording.
        if (intent.getAction().equals( GPS_POWER )) {
          sensorListener.setGpsPower( intent.getBooleanExtra("gpsPower", false) );
        }

        // Intent action to start frequency recording.
        if (intent.getAction().equals( AUDIO_POWER )) {
          sensorListener.setAudioPower( intent.getBooleanExtra("audioPower", false) );
        }

        // Receiver to adjust the sensor collection interval.
        if (intent.getAction().equals( INTERVAL )) {
          sensorListener.setSensorRefreshTime( intent.getIntExtra("sensorInterval", 250) );
        }

      }
    };

    passedContext.registerReceiver(receiver, filter);
  }

}