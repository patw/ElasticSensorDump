package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.ExecutorService;

    /**
    * Broadcast receiver for the upload runnable.
    */
class Uploads_Receiver {

        /** Main activity context. */
    private final Context passedContext;

        /** Instance of the Uploads runnable that we can update data on before indexing. */
    private final Uploads uploads;

        /** Thread pool from the service manager to execute the uploads runnable. */
    private final ExecutorService workingThreadPool;

        /** Intent action address: Boolean - Control method to shut down upload thread. */
    final static String STOP_UPLOAD_THREAD = "esd.intent.action.message.Uploads_Receiver.STOP_UPLOAD_THREAD";

        /** Intent action address: Boolean - If ESIndexer was successful indexing a record. */
    final static String INDEX_SUCCESS = "esd.intent.action.message.Uploads_Receiver.INDEX_SUCCESS";

        /** Intent action address: Boolean - Request by the service manager to start up the upload thread. */
    final static String START_UPLOAD_THREAD = "esd.intent.action.message.Uploads_Receiver.START_UPLOAD_THREAD";

        /**
        * Default constructor:
        * @param context - ESD service manager context.
        * @param sharedPreferences - The application preferences, contains URL and ID data.
        * @param passedThreadPool - Application wide thread pool. Execute uploads runnable on this.
        */
    Uploads_Receiver(Context context, SharedPreferences sharedPreferences, ExecutorService passedThreadPool ) {
        passedContext = context;
        workingThreadPool = passedThreadPool;
        uploads = new Uploads( passedContext, sharedPreferences );
    }

        /** Broadcast receiver initialization. */
    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.e(logTag+ "Up_chk", "Received indexer response");
            String logTag = "Uploads_Receiver";
            switch( intent.getAction() ){

                case START_UPLOAD_THREAD :
                    Log.e( logTag, "Submitted upload runnable." );
                    workingThreadPool.submit( uploads );
                    break;

                case STOP_UPLOAD_THREAD :
                    Log.e( logTag, "Upload thread interrupted." );
                    uploads.stopUploading();
                    break;

                case INDEX_SUCCESS:
                    Uploads.uploadSuccess = intent.getBooleanExtra("INDEX_SUCCESS", false );
                    break;

                default:
                    Log.e(logTag , "Received bad information from ACTION intent." );
                    break;
            }
        }
    };

        /** Used by the service manager to indicate if this runnable is uploading data. */
    synchronized boolean isWorking(){ return uploads.working; }

        /** */
    void registerMessageReceiver(){

        IntentFilter filter = new IntentFilter();

        filter.addAction( STOP_UPLOAD_THREAD );
        filter.addAction(INDEX_SUCCESS);
        filter.addAction( START_UPLOAD_THREAD );


        // Register this broadcast messageReceiver.
        passedContext.registerReceiver(messageReceiver, filter );
    }

        /** */
    void unRegisterUploadReceiver(){
        passedContext.unregisterReceiver( messageReceiver );
    }


}
