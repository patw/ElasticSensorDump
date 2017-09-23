package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.ExecutorService;

/**
 * Created by Gurtok on 8/28/2017.
 *
 */

class Uploads_Receiver {

    private final String logTag = "Uploads_Receiver";

    private Uploads uploads;

    /** Main activity context. */
    private Context passedContext;

    private ExecutorService workingThreadPool;

    /** Control method to shut down upload thread. */
    final static String STOP_UPLOAD_THREAD = "esd.intent.action.message.Uploads_Receiver.STOP_UPLOAD_THREAD";

    final static String UPDATE_ESD_OPTIONS = "esd.intent.action.message.Uploads_Receiver.UPDATE_ESD_OPTIONS";
    final static String START_UPLOAD_THREAD = "esd.intent.action.message.Uploads_Receiver.START_UPLOAD_THREAD";

    // Default Constructor.
    Uploads_Receiver(Context context, SharedPreferences sharedPreferences, ExecutorService passedThreadPool ) {
        passedContext = context;
        workingThreadPool = passedThreadPool;
        uploads = new Uploads( passedContext, sharedPreferences );
    }

    private BroadcastReceiver messageReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.e(logTag+ "Up_chk", "Received indexer response");
            switch( intent.getAction() ){

                case START_UPLOAD_THREAD :
                    workingThreadPool.submit( uploads );
                    break;

                case STOP_UPLOAD_THREAD :
                    Log.e( logTag, "Upload thread interrupted." );
                    uploads.stopUploading();
                    break;

                default:
                    Log.e(logTag , "Received bad information from ACTION intent." );
                    break;
            }
        }
    };

    /** Used by the service manager to indicate if this runnable is uploading data. */
    synchronized boolean isWorking(){ return uploads.working; }

    void registerMessageReceiver(){

        IntentFilter filter = new IntentFilter();

        filter.addAction( STOP_UPLOAD_THREAD );
        filter.addAction(UPDATE_ESD_OPTIONS);
        filter.addAction( START_UPLOAD_THREAD );


        // Register this broadcast messageReceiver.
        passedContext.registerReceiver(messageReceiver, filter );
    }

    void unRegisterUploadReceiver(){
        passedContext.unregisterReceiver( messageReceiver );
    }


}
