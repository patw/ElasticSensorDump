package ca.dungeons.sensordump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    /** Lazy mans ID for logging. */
    private final String logTag = "Uploads";

    /** Used to gain access to the application database. */
    private Context passedContext;
    /** A reference to the apps stored preferences. */
    private SharedPreferences sharedPreferences;

    private ElasticSearchIndexer esIndexer;

    /** Control variable to indicate if we should stop uploading to elastic. */
    private static boolean stopUploadThread = false;
    /** Control variable to indicate if the last index attempt was successful. */
    private static boolean uploadSuccess = false;
    /** Number of documents sent to server this session, default 0. */
    private static int documentsIndexed = 0;
    /** Number of failed upload transactions this session, default 0. */
    private static int uploadErrors = 0;

    /** Control variable to indicate if this runnable is currently uploading data. */
    private boolean working = false;

    /** Used to authenticate with elastic server. */
    private String esUsername = "";
    /** Used to authenticate with elastic server. */
    private String esPassword = "";
    /** Used to keep track of how many POST requests we are allowed to do each second. */
    private Long globalUploadTimer = System.currentTimeMillis();

    /** Default Constructor using the application context. */
    Uploads(Context context, SharedPreferences passedPreferences ) {
        passedContext = context;
        sharedPreferences = passedPreferences;
        esIndexer = new ElasticSearchIndexer( passedContext );
        registerMessageReceiver();
    }

    /** Used by the service manager to indicate if this runnable is uploading data. */
    synchronized boolean isWorking(){ return working; }

/* These are the different actions that the receiver can manage. */

    /** Used by the service manager to indicate if the current upload attempt was successful. */
    final static String INDEX_SUCCESS = "esd.intent.action.message.Uploads.INDEX_SUCCESS";

    /** Used by ElasticSearchIndexer to report home on the number of upload failures. */
    final static String INDEX_FAIL_COUNT = "esd.intent.action.message.Uploads.INDEX_FAIL_COUNT";

    /** Control method to shut down upload thread. */
    final static String STOP_UPLOAD_THREAD = "esd.intent.action.message.Uploads.STOP_UPLOAD_THREAD";


    private void registerMessageReceiver(){

        IntentFilter filter = new IntentFilter();

        filter.addAction( INDEX_SUCCESS );

        filter.addAction( INDEX_FAIL_COUNT );

        filter.addAction( STOP_UPLOAD_THREAD );

        BroadcastReceiver receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {

                switch( intent.getAction() ){
                    case INDEX_SUCCESS :
                        if( intent.getBooleanExtra("index_success", true) ){
                            documentsIndexed++;
                            uploadSuccess = true;
                        }
                        break;

                    case INDEX_FAIL_COUNT :
                        uploadErrors++;
                        break;

                    case STOP_UPLOAD_THREAD :
                        stopUploadThread = true;
                        break;

                    default:
                        Log.e(logTag , "Received bad information from ACTION intent." );
                        break;
                }
            }
        };
        // Register this broadcast receiver.
        passedContext.registerReceiver( receiver, filter );
    }


    /** Main work of upload runnable is accomplished here. */
    @Override
    public void run() {

        working = true;
        stopUploadThread = false;
        Intent messageIntent = new Intent();
        boolean indexAlreadyMapped = false;
        int timeoutCount = 0;
        DatabaseHelper dbHelper = new DatabaseHelper( passedContext );

        /* If we cannot establish a connection with the elastic server. */
        if( !checkForElasticHost() ){
            // This thread is not working.
            working = false;
            // We should stop the service if this is true.
            stopUploadThread = true;
            return;
        }

        /* Loop to keep uploading at a limit of 5 outs per second, while the main thread doesn't cancel. */
        while( !stopUploadThread ){

            if( !indexAlreadyMapped ){
                Log.e(logTag+"run", "Creating mapping." );

                // X-Shield security credentials.
                if (esUsername.length() > 0 && esPassword.length() > 0) {
                    messageIntent = new Intent( ElasticSearchIndexer.AUTHENTICATION );
                    passedContext.sendBroadcast( messageIntent );
                }

                // Prep esIndexer for initial MAP index.
                messageIntent.setAction( ElasticSearchIndexer.MAPPING );
                messageIntent.putExtra( "url", updateURL( "map" ));
                passedContext.sendBroadcast( messageIntent );

                // This will change to esIndexer.join(), to block the current upload task until indexing is complete.
                sleepForResults( esIndexer );

                if( uploadSuccess ){
                    indexAlreadyMapped = true;
                    Log.e(logTag+"run", "Created map successfully." );
                }

            }else if( System.currentTimeMillis() > globalUploadTimer + 200 ){

                String nextString = dbHelper.getNextCursor();

                // Prep esIndexer for standard indexing.
                messageIntent.setAction( ElasticSearchIndexer.INDEX );
                messageIntent.putExtra( "url", updateURL( "POST" ));
                passedContext.sendBroadcast( messageIntent );


                // If nextString has data.
                if ( nextString != null ) {

                    messageIntent.putExtra( "upload_string", nextString );
                    passedContext.sendBroadcast( messageIntent );

                    sleepForResults( esIndexer );

                    if( uploadSuccess ){
                        dbHelper.deleteJson();
                        globalUploadTimer = System.currentTimeMillis();
                        timeoutCount = 0;
                        //Log.e(logTag, "Successful index.");
                    }else{
                        timeoutCount++;
                    }

                    if( timeoutCount >= 9 ){
                        stopUploadThread = true;
                        Log.e(logTag, "Failed to connect 10 times, shutting down." );
                    }
                }
                onProgressUpdate();
            }
        }
        working = false;

    }

    /** Our main connection to the UI thread for communication. */
    private void onProgressUpdate() {
        Intent messageIntent = new Intent( EsdServiceManager.UPDATE_UI_UPLOAD_TASK );
        // Give this intent a what field to allow identification.
        messageIntent.putExtra( "documentsIndexed", documentsIndexed );
        messageIntent.putExtra( "uploadErrors", uploadErrors );
        passedContext.sendBroadcast( messageIntent );
    }

    /** Used to establish an order of operations. This thread needs to wait for server response. */
    private void sleepForResults( ElasticSearchIndexer esIndexer){

        while( esIndexer.isAlive() ){
            try{
                //Log.e( logTag, "Upload thread sleeping." );
                Thread.sleep(250);
            }catch( InterruptedException interEx ){
                Log.e(logTag+" run", "Failed to fall asleep." );
            }
        }
    }


    /** Extract config information from sharedPreferences.
     *  Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
     */
    private URL updateURL(String requestType) {

        // Security variables.
        boolean esSSL = sharedPreferences.getBoolean("ssl", false);

        String esHost = sharedPreferences.getString("host", "localhost");
        String esPort = sharedPreferences.getString("port", "9200");
        String esIndex = sharedPreferences.getString("index", "test_index");
        String esType = sharedPreferences.getString("type", "esd");


        esUsername = sharedPreferences.getString("user", "");
        esPassword = sharedPreferences.getString("pass", "");

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

        URL returnURL;
        String urlString = String.format( "%s%s:%s/%s", httpString ,esHost ,esPort ,esIndex );

        if( requestType.equals("POST") ){
            urlString = String.format( "%s/%s/", urlString, esType );
        }

        try{
            returnURL = new URL(urlString);
        }catch( Exception ex ){
            Log.e( logTag+"updateUrl", "FAILURE TO UPDATE URL" );
            return null;
        }

        //Log.e( logTag+" updateUrl", returnURL.toString() );
        return returnURL;
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
                //Log.e(logTag+" check host.", "Successful connection to elastic host." );

            }
        }catch( MalformedURLException malformedUrlEx ){
            Log.e( logTag+" chkHost.", "MalformedURL cause: " + malformedUrlEx.getCause() );
            malformedUrlEx.printStackTrace();
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
