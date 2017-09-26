package ca.dungeons.sensordump;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

class AudioRunnable implements Runnable {

    private final String logTag = "audioLogger";

        /** We use this to indicate to the sensor thread if we have data to send. */
    boolean hasData = false;

        /** Use this control variable to stop the recording of audio data. */
    private boolean stopThread = false;

        /** A reference to the current audio sample "loudness" in terms of percentage of mic capability.*/
    private float amplitude = 0;

        /** A reference to the current audio sample frequency. */
    private float frequency = 0;

        /** The sampling rate of the audio recording. */
    private final int SAMPLE_RATE = 44100;

        /** Short type array to feed to the recording API. */
    private short[] audioBuffer;

        /** Minimum buffer size required by AudioRecord API. */
    private int bufferSize;


    /** Default constructor.
     * Determine minimum buffer size, get data from Android audio api.
     * Set variables before executing the runnable.
     */
    AudioRunnable(){

        // Buffer size in bytes.
        bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        );

        // A check to make sure we are doing math on valid objects.
        if( bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE ){
            bufferSize = SAMPLE_RATE * 2;
        }

    }

        /** Stop the audio logging thread. */
    void setStopAudioThread( boolean power ){
        stopThread = power;
    }

        /** Main entrance. */
    @Override
    public void run() {

        // ?????
        audioBuffer = new short[bufferSize / 2];

        // New instance of Android audio recording api.
        AudioRecord audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        if( audioRecord.getState() != AudioRecord.STATE_INITIALIZED ){
            Log.e("Audio Error", "AudioRecord has not been initialized properly.");
            return;
        }

        while( !stopThread ){

            audioRecord.read( audioBuffer, 0, audioBuffer.length );

            float lowest = 0;
            float highest = 0;
            int zeroes = 0;
            int last_value = 0;


            if( audioBuffer != null ){
                // Exploring the buffer. Record the highest and lowest readings
                for( short anAudioBuffer : audioBuffer ){

                    lowest = anAudioBuffer < lowest ? anAudioBuffer : lowest;

                    highest = anAudioBuffer > highest ? anAudioBuffer : highest;

                    // Down and coming up
                    if( anAudioBuffer > 0 && last_value < 0 ){
                        zeroes++;
                    }

                    // Up and down
                    if( anAudioBuffer < 0 && last_value > 0 ){
                        zeroes++;
                    }

                    last_value = anAudioBuffer;

                    // Calculate highest and lowest peak difference as a % of the max possible
                    // value
                    amplitude = ( highest - lowest ) / 65536 * 100;

                    // Take the count of the peaks in the time that we had based on the sample
                    // rate to calculate frequency
                    if( audioBuffer != null ){
                        float seconds = (float) audioBuffer.length / SAMPLE_RATE;
                        frequency = (float) zeroes / seconds / 2;

                        hasData = true;
                    }

                }
            }
        }

        audioRecord.stop();
        audioRecord.release();
        Log.i( logTag, "Audio recording stopping.");

    }

        /** Called on the sensor thread, delivers data to the sensor message handler. */
    JSONObject getAudioData( JSONObject passedJson ){
        if(passedJson != null ){
            try{
                passedJson.put("frequency", frequency );
                passedJson.put("amplitude", amplitude);
            }catch( JSONException jsonEx ) {
                Log.e( logTag, "Error adding data to json. " );
                return passedJson;
            }
        }
        audioBuffer = new short[bufferSize / 2];
        hasData = false;
        return passedJson;
    }


}
