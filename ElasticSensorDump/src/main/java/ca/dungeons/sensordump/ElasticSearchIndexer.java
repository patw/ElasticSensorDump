package ca.dungeons.sensordump;

import java.io.DataOutputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import java.io.IOException;
import org.json.JSONObject;
import org.json.JSONException;

final class ElasticSearchIndexer extends Thread{

    private final String logTag = "eSearchIndexer";

    private Intent messageIntent;
    private Context passedContext;

    /** Elastic username. */
    private String esUsername = "";

    /** Elastic password. */
    private String esPassword = "";

    /** The "json" to be indexed. */
    private String uploadString;

    /** If we should create a mapping or not. */
    private boolean putMapping = false;

    /** Used to establish outside connection. */
    private HttpURLConnection httpCon;

    /** */
    private URL elasticUrl;

    /** Connection fail count. When this hits 10, cancel the upload thread. */
    private int connectFailCount = 0;

    /* These are the different actions that the receiver can manage. */

    /** Used by the service manager to indicate if the current upload attempt was successful. */
    final static String MAPPING = "esd.intent.action.message.esd.MAPPING";

    /** Used by ElasticSearchIndexer to report home on the number of upload failures. */
    final static String INDEX  = "esd.intent.action.message.esd.INDEX";

    /** Control method to shut down upload thread. */
    final static String AUTHENTICATION = "esd.intent.action.message.esd.AUTHENTICATION";

    /** Base constructor. */
    ElasticSearchIndexer( Context context ){
        passedContext = context;
        registerMessageReceiver();
    }

    private void registerMessageReceiver(){

        IntentFilter filter = new IntentFilter();

        filter.addAction(MAPPING);

        filter.addAction( INDEX );

        filter.addAction( AUTHENTICATION );

        BroadcastReceiver receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                messageIntent = new Intent();

                switch( intent.getAction() ){

                    case MAPPING:

                        try{
                            elasticUrl = new URL( intent.getStringExtra("url") );
                            putMapping = true;
                            createMapping();
                        }catch( MalformedURLException malFormedUrlEx ){
                            Log.e(logTag, "Count not convert string to URL." );
                        }

                        break;

                    case INDEX :
                        putMapping = false;
                        uploadString = intent.getStringExtra("upload_string");
                        index();
                        break;

                    case AUTHENTICATION :
                        esUsername = intent.getStringExtra("esUsername");
                        esPassword = intent.getStringExtra("esPassword");

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


    @Override
    public void run() {
    }

    private void indexSuccess(){
        messageIntent = new Intent( Uploads.INDEX_SUCCESS );
        passedContext.sendBroadcast( messageIntent );
    }

    private void indexFailure(){
        messageIntent = new Intent( Uploads.INDEX_FAIL_COUNT );
        passedContext.sendBroadcast( messageIntent );
    }

    /** Create a map and send to elastic for sensor index. */
    private void createMapping() {

        // Connect to elastic using PUT to make elastic understand this is a mapping.
        if( connect("PUT") ) {
            try {
                DataOutputStream dataOutputStream = new DataOutputStream(httpCon.getOutputStream());

                // Lowest json level, contains explicit typing of sensor data.
                JSONObject mappingTypes = new JSONObject();
                // Type "start_location" && "location" using pre-defined typeGeoPoint. ^^
                mappingTypes.put("start_location", new JSONObject().put("type", "geo_point" ));
                mappingTypes.put("location", new JSONObject().put("type", "geo_point" ));
                // Put the two newly typed fields under properties.
                JSONObject properties = new JSONObject().put("properties", mappingTypes);

                //String esTag = sharedPreferences.getString("tag", "phone_data");
                //      INSERT TAG ID HERE.

                // Mappings should be nested under index_type.
                JSONObject esTypeObj = new JSONObject().put("esd", properties);
                // File this new properties json under _mappings.
                JSONObject mappings = new JSONObject().put("mappings", esTypeObj);

                // Write out to elastic using the passed outputStream that is connected.
                dataOutputStream.writeBytes( mappings.toString() );

                if ( checkResponseCode() ) {

                    // Send message to upload thread about the success via intent.
                    indexSuccess();
                    Log.e(logTag + " newMap", "Mapping uploaded successfully. " + mappings.toString());
                } else {
                    // Send message to upload thread about the failure to upload via intent.
                    indexFailure();
                    Log.e(logTag + " newMap", "Failed response code check on MAPPING. " + mappings.toString());
                }

            } catch (JSONException j) {
                Log.e(logTag + " newMap", "JSON error: " + j.toString());
            } catch (IOException IoEx) {
                Log.e(logTag + " newMap", "Failed to write to outputStreamWriter.");
            }
            httpCon.disconnect();
        }else{
            Log.e(logTag, "Connection is bad." );
        }

    }

        /** Send JSON data to elastic using POST. */
    private void index() {
        // Boolean return to check if we successfully connected to the elastic host.
        if( connect("POST") ){
            // POST our documents to elastic.
            try {
                DataOutputStream dataOutputStream = new DataOutputStream( httpCon.getOutputStream() );
                dataOutputStream.writeBytes( uploadString );
                // Check status of post operation.
                if( checkResponseCode() ){
                    indexSuccess();
                    //Log.e( logTag+" esIndex.", "Uploaded: " + uploadString );
                    return;
                }
            }catch( IOException IOex ){
                // Error writing to httpConnection.
                Log.e( logTag+" esIndex.", IOex.getMessage() );
            }

            Log.e(logTag+" esIndex.", uploadString );
            indexFailure();
            httpCon.disconnect();
        }

    }


    /** Open a connection with the server. */
    private boolean connect(String verb){

    if( connectFailCount % 10 != 0 ){
        // Send authentication if required
        if (esUsername.length() > 0 && esPassword.length() > 0) {
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(esUsername, esPassword.toCharArray());
                }
            });
        }

        // Establish connection.
        try {
            httpCon = (HttpURLConnection) elasticUrl.openConnection();
            httpCon.setConnectTimeout(2000);
            httpCon.setReadTimeout(2000);
            httpCon.setDoOutput(true);
            httpCon.setDoInput(true);
            httpCon.setRequestMethod(verb);
            httpCon.connect();
            //Log.e( logTag+" connect.", "Connected to ESD.");
            // Reset the failure count.
            connectFailCount = 0;
            return true;
        }catch(MalformedURLException urlEx){
            Log.e( logTag+" connect.", "Error building URL.");
            connectFailCount++;
        }catch (IOException IOex) {
            Log.e( logTag+" connect.", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
            connectFailCount++;
        }
    }else{
        Log.e(logTag, "Failure to connect. Aborting!" );
    }
        return false;
    }

    private boolean checkResponseCode(){

        String responseMessage = "ResponseCode placeholder.";
        int responseCode = 0;

            try{

                responseMessage = httpCon.getResponseMessage();
                responseCode = httpCon.getResponseCode();

                if (200 <= responseCode && responseCode <= 299 || responseCode == 400) {
                    httpCon.disconnect();
                    return true;
                }else{
                    throw new IOException( "" );
                }

            }catch( IOException ioEx ){

                // Something bad happened. I expect only the finest of 200's
                Log.e( logTag+" response", String.format("%s%s\n%s%s\n%s",
                        "Bad response code: ", responseCode,
                        "Response Message: ", responseMessage,
                        httpCon.getURL() + " request type: " + httpCon.getRequestMethod() )// End string.
                );


            }

        httpCon.disconnect();
        return false;
    }

}
