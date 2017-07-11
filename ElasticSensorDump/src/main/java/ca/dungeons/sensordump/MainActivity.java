package ca.dungeons.sensordump;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import android.net.ConnectivityManager;

import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;


/**
 * Elastic Sensor Dump.
 * Enumerates the sensors from an android device.
 * Record the sensor data and upload it to your elastic search server.
 */
public class MainActivity extends Activity{

    /** Global SharedPreferences object. */
    public static SharedPreferences sharedPrefs;

    /** Head honcho. This class is the background service that is the infrastructure of ESD. */
    EsdServiceManager serviceManager;
    /** We use this broadcast receiver to communicate with the service manager. */
    EsdServiceReceiver serviceReceiver;

    /** Do NOT record more than once every 50 milliseconds. Default value is 250ms. */
    private static final int MIN_SENSOR_REFRESH = 50;
    /** Refresh time in milliseconds. Default = 250ms.*/
    private int sensorRefreshTime = 250;

    /** Number of sensor readings this session */
    public static long sensorReadings, documentsIndexed, gpsReadings, uploadErrors;


      /**
       * Set contentView to portrait, and lock it that way.
       * Build main activity buttons.
       * Get a list of all available sensors on the device and store in array.
       * @param savedInstanceState A generic object.
       */
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate( savedInstanceState);
        setContentView( R.layout.activity_main);
        setRequestedOrientation( ActivityInfo.SCREEN_ORIENTATION_LOCKED );

        buildButtonLogic();
        updateScreen();
    }

    private void startServiceManager(){
        serviceManager = new EsdServiceManager();
        serviceReceiver = new EsdServiceReceiver();
        serviceManager.startService( new Intent( EsdServiceReceiver.IDLE_MESSAGE ));
    }

      /**
       * Update the display with readings/written/errors.
       * Need to update UI based on the passed data intent.
       *
       */

    void updateScreen() {
        TextView mainBanner = (TextView) findViewById(R.id.main_Banner);

        TextView sensorTV = (TextView) findViewById(R.id.sensor_tv);
        TextView documentsTV = (TextView) findViewById(R.id.documents_tv);
        TextView gpsTV = (TextView) findViewById(R.id.gps_TV);
        TextView errorsTV = (TextView) findViewById(R.id.errors_TV);

        sensorTV.setText( String.valueOf(sensorReadings) );
        documentsTV.setText( String.valueOf( documentsIndexed ) );
        gpsTV.setText( String.valueOf( gpsReadings ) );
        errorsTV.setText( String.valueOf( uploadErrors ) );

    }

      /**
       * Go through the sensor array and light them all up
       * btnStart: Click a button, get some sensor data.
       * ibSetup: Settings screen.
       * seekBar: Adjust the collection rate of data.
       * gpsToggle: Turn gps collection on/off.
       * audioToggle: Turn audio recording on/off.
       */
    void buildButtonLogic() {

        final ToggleButton startButton = (ToggleButton) findViewById(R.id.toggleStart);
        startButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged( CompoundButton buttonView, boolean isChecked ) {
                if( isChecked ){
                    Log.e("MainActivity", "Start button ON !");
                    startButton.setBackgroundResource( R.drawable.main_button_shape_on);
                }else{
                    Log.e("MainActivity", "Start button OFF !");
                    startButton.setBackgroundResource( R.drawable.main_button_shape_off);
                }
                // Broadcast to the service manager that we are toggling sensor logging.
                sendBroadcast(new Intent( EsdServiceReceiver.SENSOR_MESSAGE ));
            }
        });

        final ImageButton settingsButton = (ImageButton) findViewById(R.id.settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            final Intent settingsIntent = new Intent(getBaseContext(), SettingsActivity.class);
            startActivity(settingsIntent);
            }
        });


        final ToggleButton gpsToggle = (ToggleButton) findViewById(R.id.toggleGPS);
        gpsToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            // If gps button is turned ON.
            if( isChecked ){
                gpsToggle.setBackgroundResource( R.drawable.main_button_shape_on);
            // If gps button has been turned OFF.
            }else{
                gpsToggle.setBackgroundResource( R.drawable.main_button_shape_off);
            }
            // Broadcast to the service manager that we are toggling gps logging.
            sendBroadcast(new Intent( EsdServiceReceiver.GPS_MESSAGE ));
            }
        });

        final ToggleButton audioToggle = (ToggleButton) findViewById( R.id.toggleAudio );
        audioToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            // If audio button is turned ON.
            if( isChecked ){
                audioToggle.setBackgroundResource( R.drawable.main_button_shape_on);
            }else{
                audioToggle.setBackgroundResource( R.drawable.main_button_shape_off);
            }
            // Broadcast to the service manager that we are toggling audio logging.
            sendBroadcast( new Intent( EsdServiceReceiver.AUDIO_MESSAGE ));

            }
        });

        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        final TextView tvSeekBarText = (TextView) findViewById(R.id.TickText);
        tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + seekBar.getProgress() * 10 + getString(R.string.milliseconds));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if ( progress * 10 < MIN_SENSOR_REFRESH ) {
                    seekBar.setProgress( MIN_SENSOR_REFRESH / 5 );
                    Toast.makeText(getApplicationContext(),"Minimum sensor refresh is 50 ms",Toast.LENGTH_SHORT).show();
                }else{
                    sensorRefreshTime = progress * 10;
                }

                Intent intervalIntent = new Intent( EsdServiceReceiver.INTERVAL );
                intervalIntent.putExtra("sensorInterval", sensorRefreshTime );
                sendBroadcast( intervalIntent );

                tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + sensorRefreshTime + getString(R.string.milliseconds));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {} //intentionally blank

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {} //intentionally blank
        });

    }





    /** If our activity is paused, we need to close out the resources in use. */
    @Override
    protected void onPause() {
        super.onPause();

    }

    /** When the activity starts or resumes, we start the upload process immediately.
     *  If we were logging, we need to start the logging process.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // We need to check the status of the service manager here, and start it as needed.
        updateScreen();
    }





}
