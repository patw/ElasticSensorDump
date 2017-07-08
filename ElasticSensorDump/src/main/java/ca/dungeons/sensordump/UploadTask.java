package ca.dungeons.sensordump;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Message;
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
class UploadTask extends Thread{

    /** Used to identify the origin of the message sent to the UI thread. */
    static final int UPLOAD_TASK_ID = 123456;
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
    /** Reference handler to send messages back to the UI thread. */
    private Handler uiHandler;
    /** Number of documents sent to server this session, default 0. */
    private static int documentsIndexed = 0;
    /** Number of failed upload transactions this session, default 0. */
    private static int uploadErrors = 0;

    /** Default Constructor using the application context. */
    UploadTask(Context context, Handler handler, SharedPreferences passedPreferences ) {
        passedContext = context;
        uiHandler = handler;
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
    void stopSensorThread(){ stopUploadThread= true; }

    static void indexSuccess(boolean test ){ uploadSuccess = test; }

    long getDatabasePopulation(){
        DatabaseHelper databaseHelper = new DatabaseHelper( passedContext );
        Long databaseEntries = databaseHelper.databaseEntries();
        databaseHelper.close();
        return databaseEntries;
    }

    @Override
    public void run() {

        if( !checkForElasticHost() ){
            this.stopSensorThread();
            return;
        }

        DatabaseHelper dbHelper = new DatabaseHelper(passedContext);
        URL destinationURL = updateURL();
        boolean indexAlreadyMapped = sharedPreferences.getBoolean("IndexMapped", false);
        ElasticSearchIndexer esIndexer;

        // Loop to keep uploading at a limit of 5 outs per second, while the main thread doesn't cancel.
        while( !stopUploadThread ){
            if( !indexAlreadyMapped ){
                esIndexer = new ElasticSearchIndexer(destinationURL);
                if (esUsername.length() > 0 && esPassword.length() > 0) {
                    esIndexer.setAuthorization(esUsername, esPassword);
                }
                esIndexer.start();
                sharedPreferences.edit().putBoolean("IndexMapped", true).apply();
            } else if (System.currentTimeMillis() > globalUploadTimer + 200) {

                String nextString = dbHelper.getNextCursor();

                esIndexer = new ElasticSearchIndexer(nextString, destinationURL);

                if (esUsername.length() > 0 && esPassword.length() > 0) {
                    esIndexer.setAuthorization(esUsername, esPassword);
                }

                // If nextString has data.
                if ( nextString != null ) {
                    esIndexer.start();
                    while( esIndexer.isAlive() ){
                        // Wait til the indexer finishes.
                    }
                    if( uploadSuccess )
                        dbHelper.deleteJson();
                }
                Message outMessage = uiHandler.obtainMessage();
                outMessage.what = UPLOAD_TASK_ID;
                outMessage.arg1 = documentsIndexed;
                outMessage.arg2 = uploadErrors;

                uiHandler.sendMessage(outMessage);
                globalUploadTimer = System.currentTimeMillis();
            }
        }

    }


    /** Extract config information from sharedPreferences.
     *  Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
     */
    private URL updateURL() {

        // Security variables.
        boolean esSSL = sharedPreferences.getBoolean("ssl", false);

        String esHost = sharedPreferences.getString("host", "localhost");
        String esPort = sharedPreferences.getString("port", "9200");
        String esIndex = sharedPreferences.getString("index", "test_index");
        //esTag = sharedPreferences.getString("tag", "phone_data");
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

        URL returnURL = null;

        // Default currently is non-secure. Will change that asap.
        String httpString = "http://";
        if( esSSL ){
            httpString = "https://";
        }

        // Default is POST. All but the mappings are POSTed.
        String urlString = String.format( "%s%s:%s/%s/%s/", httpString ,esHost ,esPort ,esIndex, esType);

        try{
            returnURL = new URL(urlString);
        }catch( MalformedURLException malFormedUrlEx){
            Log.e("UploadThread-CheckHost", "Failed to create a new URL. Bad string?" );
        }

        return returnURL;
    }

    private boolean checkForElasticHost(){

        boolean responseCodeSuccess = false;

        HttpURLConnection httpConnection = null;
        String esHost, esPort;
        URL esUrl;
        esHost = sharedPreferences.getString("host", "localhost" );
        esPort = sharedPreferences.getString("port", "9200" );
        String esHostUrlString = String.format("http://%s:%s/", esHost, esPort );

        try{
            //Log.e("UploadThread-CheckHost", esHostUrlString ); // DIAGNOSTICS
            esUrl = new URL( esHostUrlString );

            httpConnection = (HttpURLConnection) esUrl.openConnection();
            httpConnection.setConnectTimeout(2000);
            httpConnection.setReadTimeout(2000);
            httpConnection.connect();

            int responseCode = httpConnection.getResponseCode();
            if( responseCode >= 200 && responseCode <= 299 ){
                responseCodeSuccess = true;
            }
        }catch( MalformedURLException malformedUrlEx ){
            Log.e("UploadThread-CheckHost", "MalformedURL cause: " + malformedUrlEx.getCause() );
            malformedUrlEx.printStackTrace();
        }catch(IOException IoEx ){

            Log.e("UploadThread-CheckHost", "Failure to open connection cause: " + IoEx.getMessage());
        }

        if( httpConnection != null ){
            httpConnection.disconnect();
        }

        // Returns true if the response code was valid.
        return responseCodeSuccess;
    }


}
