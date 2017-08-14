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
class UploadThread extends Thread{

    private final String logTag = "UploadThread";

    /** Used to gain access to the application database. */
    private Context passedContext;

    private boolean stopUploadThread = false;
    private static boolean uploadSuccess = false;

    /** A reference to the apps stored preferences. */
    private SharedPreferences sharedPreferences;
    /** Used to authenticate with elastic server. */
    private String esUsername = "";
    /** Used to authenticate with elastic server. */
    private String esPassword = "";
    /** Used to keep track of how many POST requests we are allowed to do each second. */
    private Long globalUploadTimer = System.currentTimeMillis();
    /** Number of documents sent to server this session, default 0. */
    private static int documentsIndexed = 0;
    /** Number of failed upload transactions this session, default 0. */
    private static int uploadErrors = 0;

    /** Default Constructor using the application context. */
    UploadThread(Context context, SharedPreferences passedPreferences ) {
        passedContext = context;
        sharedPreferences = passedPreferences;
    }

    /** Used by ElasticSearchIndexer to report home on upload status. */
    static void indexSuccessCount(){
        documentsIndexed++;
    }
    /** Used by ElasticSearchIndexer to report home on upload status. */
    static void indexFailureCount(){
        uploadErrors++;
    }
    /** Control method to shut down upload thread. */
    void stopUploadThread(){ stopUploadThread= true; }

    static void indexSuccess(boolean test ){ uploadSuccess = test; }

    int getDatabasePopulation(){
        DatabaseHelper databaseHelper = new DatabaseHelper( passedContext );
        Long databaseEntries = databaseHelper.databaseEntries();
        databaseHelper.close();
        return Integer.valueOf( databaseEntries.toString() );
    }

    @Override
    public void run() {

        if( !checkForElasticHost() ){
            this.stopUploadThread();
            return;
        }

        URL destinationURL;
        boolean indexAlreadyMapped = false;
        DatabaseHelper dbHelper = new DatabaseHelper( passedContext );



        ElasticSearchIndexer esIndexer;

        // Loop to keep uploading at a limit of 5 outs per second, while the main thread doesn't cancel.
        while( !stopUploadThread ){

            if( !indexAlreadyMapped ){
                Log.e(logTag+"run", "Creating mapping." );
                destinationURL = updateURL( "map" );
                esIndexer = new ElasticSearchIndexer(destinationURL);

                // X-Shield security credentials.
                if (esUsername.length() > 0 && esPassword.length() > 0) {
                    esIndexer.setAuthorization(esUsername, esPassword);
                }

                // Start ElasticSearchIndexer and wait for a response.
                esIndexer.start();
                // This will change to esIndexer.join(), to block the current upload task until indexing is complete.
                sleepForResults( esIndexer );

                if( uploadSuccess ){
                    indexAlreadyMapped = true;
                    Log.e(logTag+"run", "Created map successfully." );
                }

            }else if( System.currentTimeMillis() > globalUploadTimer + 200 ){

                String nextString = dbHelper.getNextCursor();
                destinationURL = updateURL("POST");

                esIndexer = new ElasticSearchIndexer(nextString, destinationURL);

                // If nextString has data.
                if ( nextString != null ) {

                    esIndexer.start();
                    sleepForResults( esIndexer );

                    if( uploadSuccess ){
                        dbHelper.deleteJson();
                        globalUploadTimer = System.currentTimeMillis();
                    }
                }
                onProgressUpdate();
            }
        }

    }

    /** Our main connection to the UI thread for communication. */
    private void onProgressUpdate() {
        Intent messageIntent = new Intent( EsdServiceManager.UPDATE_UI_UPLOAD_TASK );
        // Give this intent a what field to allow identification.
        messageIntent.putExtra( "documentsIndexed", documentsIndexed );
        messageIntent.putExtra( "uploadErrors", uploadErrors );
        messageIntent.putExtra( "databasePopulation", getDatabasePopulation() );
        passedContext.sendBroadcast( messageIntent );
    }

    private void sleepForResults( ElasticSearchIndexer esIndexer){

        while( esIndexer.isAlive() ){
            try{
                Thread.sleep(50);
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
            //Log.e("UploadThread-CheckHost", esHostUrlString); // DIAGNOSTICS
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
            IoEx.printStackTrace();
            Log.e( logTag+" chkHost.", "Failure to open connection cause: " + IoEx.getMessage() + " " + responseCode );
        }

        if( httpConnection != null ){
            httpConnection.disconnect();
        }

        // Returns true if the response code was valid.
        return responseCodeSuccess;
    }


}
