package ca.dungeons.sensordump;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

    /**
    * A class to start a thread upload the database to Kibana.
    * @author Gurtok.
    * @version First version of upload Async thread.
    */
class Uploads implements Runnable{

        /** ID for logcat. */
    private final String logTag = "Uploads";

        /** Used to gain access to the application database. */
    private final Context serviceContext;

        /** A reference to the apps stored preferences. */
    private final SharedPreferences sharedPreferences;

        /** */
    private final ElasticSearchIndexer esIndexer;

        /** Static variable for the indexer thread to communicate success or failure of an index attempt. */
    static boolean uploadSuccess = false;

        /** Control variable to indicate if we should stop uploading to elastic. */
    private static boolean stopUploadThread = false;

        /** Control variable to indicate if this runnable is currently uploading data. */
    boolean working = false;

        /** Used to keep track of how many POST requests we are allowed to do each second. */
    private Long globalUploadTimer = System.currentTimeMillis();

        /** Default Constructor using the application context. */
    Uploads(Context context, SharedPreferences passedPreferences ) {
        serviceContext = context;
        sharedPreferences = passedPreferences;
        esIndexer = new ElasticSearchIndexer( context );
    }

        /** Main class entry. The data we need has already been updated. So just go nuts. */
    @Override
    public void run() {
        startUploading();
    }

        /** Control variable to halt the whole thread. */
    void stopUploading(){  stopUploadThread = true;  }

        /** Main work of upload runnable is accomplished here. */
    private void startUploading() {

        Log.e( logTag, "Started upload thread." );

        working = true;
        stopUploadThread = false;

        int timeoutCount = 0;

        DatabaseHelper dbHelper = new DatabaseHelper(serviceContext);

        /* If we cannot establish a connection with the elastic server. */
        if( !checkForElasticHost() ){
            // This thread is not working.
            working = false;
            // We should stop the service if this is true.
            stopUploadThread = true;
            Log.e(logTag, "No elastic host." );
            return;
        }

        /* Loop to keep uploading. */
        while( !stopUploadThread ){

        /* A limit of 5 outs per second */
            if( System.currentTimeMillis() > globalUploadTimer + 200 ){

                updateIndexerUrl();

                uploadSuccess = false;
                String nextString = dbHelper.getNextCursor();

                // If nextString has data.
                if ( nextString != null ) {

                    esIndexer.uploadString = nextString;

                    try{
                        // Start the indexing thread, and join to wait for it to finish.
                        esIndexer.start();
                        esIndexer.join();
                    }catch( InterruptedException interEx ){
                        Log.e(logTag, "Failed to join ESI thread, possibly not running." );
                    }

                    if( uploadSuccess ){

                        globalUploadTimer = System.currentTimeMillis();
                        timeoutCount = 0;
                        indexSuccess( true );
                        dbHelper.deleteJson();
                        //Log.e(logTag, "Successful index.");
                    }else{

                        timeoutCount++;
                        indexSuccess( false );
                        if( timeoutCount > 9 ){
                            Log.e(logTag, "Failed to index 10 times, shutting down." );
                            stopUploading();
                        }
                    }
                }
            }
        }
    working = false;
    }

        /** Our main connection to the UI thread for communication. */
    private void indexSuccess(boolean result ){
        Intent messageIntent = new Intent( EsdServiceReceiver.INDEX_SUCCESS );
        messageIntent.putExtra( "INDEX_SUCCESS", result );
        serviceContext.sendBroadcast( messageIntent );
    }

        /** Extract config information from sharedPreferences.
        *  Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
        */
    private void updateIndexerUrl() {

        // Security variables.
        boolean esSSL = sharedPreferences.getBoolean("ssl", false);
        String esUsername = sharedPreferences.getString( "user", "" );
        String esPassword = sharedPreferences.getString( "pass", "" );

        // X-Pack security credentials.
        if (esUsername.length() > 0 && esPassword.length() > 0) {
            esIndexer.esUsername = esUsername;
            esIndexer.esPassword = esPassword;
        }

        String esHost = sharedPreferences.getString("host", "localhost");
        String esPort = sharedPreferences.getString("port", "9200");
        String esIndex = sharedPreferences.getString("index", "test_index");
        String esType = sharedPreferences.getString("type", "esd");

        // Tag the current date stamp on the index name if set in preferences
        // Thanks GlenRSmith for this idea
        if (sharedPreferences.getBoolean("index_date", false)) {
            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
            String dateString = logDateFormat.format(logDate);
            esIndex = esIndex + "-" + dateString;
        }

        // Default currently is non-secure. Will change that asap.
        String httpString = "http://";
        if( esSSL ){
            httpString = "https://";
        }
        String mappingURL = String.format( "%s%s:%s/%s", httpString ,esHost ,esPort ,esIndex );

        // Note the different URLs. Regular post ends with type. Mapping ends with index ID.
        String postingURL = String.format( "%s%s:%s/%s/%s", httpString ,esHost ,esPort ,esIndex, esType );

        try{
            esIndexer.mapUrl = new URL( mappingURL );
            esIndexer.postUrl = new URL( postingURL );
        }catch( MalformedURLException malformedUrlEx ){
            Log.e(logTag, "Failed to update URLs." );
            esIndexer.mapUrl = null;
            esIndexer.postUrl = null;
        }

    }

        /** Helper method to determine if we currently have access to an elastic server to upload to. */
    private boolean checkForElasticHost(){

        boolean responseCodeSuccess = false;
        int responseCode = 0;

        HttpURLConnection httpConnection = null;
        String esHost, esPort;
        URL esUrl;
        esHost = sharedPreferences.getString("host", "192.168.1.120" );
        esPort = sharedPreferences.getString("port", "9200" );
        String esHostUrlString = String.format("http://%s:%s/", esHost, esPort );

        try{
            //Log.e("Uploads-CheckHost", esHostUrlString); // DIAGNOSTICS
            esUrl = new URL( esHostUrlString );

            httpConnection = (HttpURLConnection) esUrl.openConnection();

            httpConnection.setConnectTimeout(2000);
            httpConnection.setReadTimeout(2000);
            httpConnection.connect();

            responseCode = httpConnection.getResponseCode();
            if( responseCode >= 200 && responseCode <= 299 ){
                responseCodeSuccess = true;
            }
        }catch( MalformedURLException malformedUrlEx ){
            malformedUrlEx.printStackTrace();
            Log.e( logTag+" chkHost.", "MalformedURL cause: " + malformedUrlEx.getCause() );
        }catch(IOException IoEx ){
            //IoEx.printStackTrace();
            Log.e( logTag+" chkHost.", "Failure to open connection cause: " + IoEx.getMessage() + " " + responseCode );
        }

        if( httpConnection != null ){
            httpConnection.disconnect();
        }

        // Returns true if the response code was valid.
        return responseCodeSuccess;
    }

}
