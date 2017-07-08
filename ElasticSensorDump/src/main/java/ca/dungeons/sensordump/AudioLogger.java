package ca.dungeons.sensordump;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

class AudioLogger extends Thread {

    /* Unique ID for broadcasting information to UI thread. */
    //static final int AUDIO_THREAD_ID = 246810;
    /** We use this to indicate to the sensor thread if we have data to send. */
    boolean hasData = false;

    /** Indicates if recording / playback should stop. */
    private boolean stopThread = false;

    /** A reference to Androids built in audio recording API. */
    private AudioRecord audioRecord;

    /** A reference to the current audio sample "loudness" in terms of percentage of mic capability.*/
    private float amplitude = 0;
    /** A reference to the current audio sample frequency. */
    private float frequency = 0;
    /** The sampling rate of the audio recording. */
    private final int SAMPLE_RATE = 44100;
    /** Short type array to feed to the recording API. */
    private short[] audioBuffer;

    /** Constructor.
     * Here we set static variables used for all recording. */
    AudioLogger(){
        // Buffer size in bytes.
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        );
        // A check to make sure we are doing math on valid objects.
        if( bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE ){
            bufferSize = SAMPLE_RATE * 2;
        }

        // ?????
        audioBuffer = new short[bufferSize / 2];

        // New instance of Android audio recording api.
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

    }

    void setStopAudioThread( boolean power ){
        stopThread = power;
    }

    @Override
    public void run() {

        if( audioRecord.getState() != AudioRecord.STATE_INITIALIZED ){
            Log.e("Audio Error", "Can't record audio.");
            return;
        }

        if( !stopThread){
            stopRecording();
        }

        audioRecord.read( audioBuffer, 0, audioBuffer.length );

        float lowest = 0;
        float highest = 0;
        int zeroes = 0;
        int last_value = 0;

        // Exploring the buffer. Record the highest and lowest readings
        for( short anAudioBuffer : audioBuffer ){

            // Detect lowest in sample
            if( anAudioBuffer < lowest ){
                lowest = anAudioBuffer;
            }

            // Detect highest in sample
            if( anAudioBuffer > highest ){
                highest = anAudioBuffer;
            }

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
            amplitude = (highest - lowest) / 65536 * 100;

            // Take the count of the peaks in the time that we had based on the sample
            // rate to calculate frequency
            float seconds = (float) audioBuffer.length / (float) SAMPLE_RATE;
            frequency = (float) zeroes / seconds / 2;

            hasData = true;
        }

    }

    /** Called on the sensor thread, delivers data to the sensor message handler. */
    JSONObject getAudioData( JSONObject passedJson ){
        if(passedJson != null ){
            try{
                passedJson.put("frequency", frequency );
                passedJson.put("amplitude", amplitude);
            }catch( JSONException jsonEx ) {
                Log.e( "AudioLogger", "Error adding data to json. " );
                return passedJson;
            }
        }
        audioBuffer = null;
        hasData = false;
        return passedJson;
    }

    /** Control method to stop recording audio and release the resources. */
    private void stopRecording() {
        audioRecord.stop();
        audioRecord.release();
        stopThread = false;
        Log.i("Audio", "Audio recording stopping.");
    }


}
