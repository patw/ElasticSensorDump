package ca.dungeons.sensordump;

import java.io.DataOutputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.IOException;

import org.json.JSONObject;
import org.json.JSONException;

class ElasticSearchIndexer extends Thread{

    /** Used to identify which class is writing to logCat. */
    private final String logTag = "eSearchIndexer";

    /** The context of the service manager. */
    private Context passedContext;

    /** Elastic username. */
    String esUsername = "";
    /** Elastic password. */
    String esPassword = "";

    /** Used to establish outside connection. */
    private HttpURLConnection httpCon;

    /** Connection fail count. When this hits 10, cancel the upload thread. */
    private int connectFailCount = 0;

    /** The URL we use to post data to the server. */
    URL postUrl;
    /** The URL we use to create an index and PUT a mapping schema on it. */
    URL mapUrl;

    /** Variable to keep track if this instance of the indexer has submitted a map. */
    private boolean alreadySentMapping;

    /** A variable to hold the JSON string to be uploaded. */
    String uploadString = "";

    /** Base constructor. */
    ElasticSearchIndexer( Context context ){
        passedContext = context;
    }

    /** This run method is executed upon each index start. */
    @Override
    public void run() {
        if( !alreadySentMapping ){
            createMapping();
        }

        if( !uploadString.equals("") && alreadySentMapping ){
            index( uploadString );
        }
    }

    /** Send messages to Upload thread and ESD service thread to indicate result of index.*/
    private void indexSuccess( boolean result ){

        Intent messageIntent;

        messageIntent = new Intent( Uploads_Receiver.INDEX_SUCCESS );
        messageIntent.putExtra("INDEX_SUCCESS", result );
        passedContext.sendBroadcast( messageIntent );

        messageIntent = new Intent( EsdServiceReceiver.INDEX_SUCCESS );
        messageIntent.putExtra("INDEX_SUCCESS", result );
        passedContext.sendBroadcast( messageIntent );
    }


    /** Create a map and send to elastic for sensor index. */
    private void createMapping() {
        Log.e(logTag + " newMap", "Mapping uploading." );
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

                // Mappings should be nested under index_type.
                JSONObject esTypeObj = new JSONObject().put( "esd", properties);
                // File this new properties json under _mappings.
                JSONObject mappings = new JSONObject().put("mappings", esTypeObj);

                // Write out to elastic using the passed outputStream that is connected.
                dataOutputStream.writeBytes( mappings.toString() );

                if ( checkResponseCode() ) {
                    alreadySentMapping = true;
                } else {
                    // Send message to upload thread about the failure to upload via intent.
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
        Log.e(logTag, "Finished mapping." );
    }

    /** Send JSON data to elastic using POST. */
    private void index( String uploadString ) {
        //Log.e(logTag+" index", "Index STARTED: " + uploadString );

        // Boolean return to check if we successfully connected to the elastic host.
        if( connect("POST") ){
            // POST our documents to elastic.
            try {
                DataOutputStream dataOutputStream = new DataOutputStream( httpCon.getOutputStream() );
                dataOutputStream.writeBytes( uploadString );
                // Check status of post operation.
                if( checkResponseCode() ){
                    indexSuccess( true );
                }else{
                    Log.e(logTag+" esIndex.", "Uploaded string FAILURE!"  );
                    indexSuccess( false );
                }
            }catch( IOException IOex ){
                // Error writing to httpConnection.
                Log.e( logTag+" esIndex.", IOex.getMessage() );
            }
            httpCon.disconnect();
        }
    }

    /** Open a connection with the server. */
    private boolean connect(String verb){
        //Log.e( logTag+" connect.", "Connecting." );
        if( connectFailCount == 0 || connectFailCount % 10 != 0 ){

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

                if( verb.equals("PUT") ) {
                    httpCon = (HttpURLConnection) mapUrl.openConnection();
                }else{
                    httpCon = (HttpURLConnection) postUrl.openConnection();
                }

                httpCon.setConnectTimeout(2000);
                httpCon.setReadTimeout(2000);
                httpCon.setDoOutput(true);
                httpCon.setDoInput(true);
                httpCon.setRequestMethod( verb );
                httpCon.connect();

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
    // If it got this far, it failed.
    return false;
    }

    /** Helper class to determine if an individual indexing operation was successful.
     * "I expect only the finest of 200s" - Ademara*/
    private boolean checkResponseCode(){

        String responseMessage = "ResponseCode placeholder.";
        int responseCode = 0;

            try{

                responseMessage = httpCon.getResponseMessage();
                responseCode = httpCon.getResponseCode();

                if (200 <= responseCode && responseCode <= 299 || responseCode == 400 ) {
                    //Log.e( logTag, "Success with response code: " + responseMessage + responseCode );

                    if( responseCode == 400 ){
                        Log.e( logTag, "Index already exists. Skipping map." );
                    }

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
