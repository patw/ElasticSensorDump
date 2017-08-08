package ca.dungeons.sensordump;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import android.util.Log;
import java.io.IOException;
import org.json.JSONObject;
import org.json.JSONException;

final class ElasticSearchIndexer extends Thread{

    private final String logTag = "eSearchIndexer";

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
    private OutputStream outputStream;


        /** Base constructor. */
    ElasticSearchIndexer(String indexString, URL url ){
        uploadString = indexString;
        elasticUrl = url;
    }
    /** Base constructor. */
    ElasticSearchIndexer( URL url ){
        elasticUrl = url;
        putMapping = true;
    }

    @Override
    public void run() {
        if( putMapping ){
            createMapping();
        }else if( uploadString != null ){
            index();
        }
    }

    void setAuthorization( String userName, String password ){
        esUsername = userName;
        esPassword = password;
    }

    /** Create a map and send to elastic for sensor index. */
    private void createMapping() {
        // Connect to elastic using PUT to make elastic understand this payload is a mapping.
        connect("PUT");
        if( outputStream == null ) {
            return;
        }

        String esType = "esd";
        DataOutputStream dataOutputStream = new DataOutputStream( outputStream );
        try {
            // GPS coordinates -- A new Type.
            JSONObject typeGeoPoint = new JSONObject().put("type", "geo_point");
            // Lowest json level, contains explicit typing of sensor data.
            JSONObject mappingTypes = new JSONObject();
            // Type "start_location" && "location" using pre-defined typeGeoPoint. ^^
            mappingTypes.put("start_location", typeGeoPoint);
            mappingTypes.put("location", typeGeoPoint);
            // Put the two newly typed fields under properties.
            JSONObject properties = new JSONObject().put("properties", mappingTypes);
            // Mappings should be nested under index_type.
            JSONObject esTypeObj = new JSONObject().put(esType, properties);
            // File this new properties json under _mappings.
            JSONObject mappings = new JSONObject().put("mappings", esTypeObj);

            // Write out to elastic using the passed outputStream that is connected.
            dataOutputStream.writeBytes(  mappings.toString() );
            UploadTask.indexSuccess( true );
            Log.e( logTag+"newMap.", "Mapping uploaded successfully. " + mappings.toString() );
        }catch(JSONException j) {
            Log.e( logTag+"newMap.", "JSON error: " + j.toString());
        }catch(IOException IoEx) {
            Log.e( logTag+"newMap.", "Failed to write to outputStreamWriter." );
        }


    }

        /** Send JSON data to elastic using POST. */
    private void index() {
        // POST our documents to elastic.
        connect("POST");
        if( outputStream != null ){
            try {
                DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
                dataOutputStream.writeBytes( uploadString );
                // Check status of post operation.
                if( checkResponseCode() ){
                    UploadTask.indexSuccessCount();
                    UploadTask.indexSuccess(true);
                    Log.e( logTag+" esIndex.", "Uploaded: " + uploadString );
                    return;
                }
            }catch( IOException IOex ){
                // Error writing to httpConnection.
                Log.e( logTag+" esIndex.", IOex.getMessage() );
            }
            Log.e(logTag+" esIndex.", uploadString );
            UploadTask.indexFailureCount();
            UploadTask.indexSuccess(false);
        }
        if( httpCon != null ){
            httpCon.disconnect();
        }

    }


    /** Open a connection with the server. */
    private void connect(String verb){

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
            httpCon.setRequestMethod(verb);
            httpCon.setRequestProperty( "Content-Type", "text/plain; charset=utf-8");
            httpCon.connect();
            outputStream = httpCon.getOutputStream();
        }catch(MalformedURLException urlEx){
            Log.e( logTag+" connect.", "Error building URL.");
        }catch (IOException IOex) {
            Log.e( logTag+" connect.", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
        }
    }

    private boolean checkResponseCode(){
        String responseMessage = "ResponseCode placeholder.";
        int responseCode = 0;
        String errorString = "Error Message: ";
        String errorStreamMessage;

        BufferedReader errorStream = null;
        InputStream httpInputStream = httpCon.getErrorStream();

        InputStreamReader inputStreamReader;

        if( httpInputStream == null ){
            Log.e(logTag+" response", "Input stream is null." );
        }

        try{

            if( httpInputStream != null ){

                responseMessage = httpCon.getResponseMessage();
                responseCode = httpCon.getResponseCode();
                inputStreamReader = new InputStreamReader( httpInputStream );
                errorStream = new BufferedReader( inputStreamReader );
            }

            if( errorStream != null ){

                while( ( errorStreamMessage = errorStream.readLine() ) != null ){
                    errorString = errorString + " : " + errorStreamMessage;
                }
                errorStream.close();
            }

            if( 200 <= responseCode && responseCode <= 299 ){

                httpCon.disconnect();
                return true;
            }
        }catch( IOException ioEx ){
            Log.e( logTag+" response", "Failed to retrieve response codes for REST operation." );
        }


        // Something bad happened. I expect only the finest of 200's
        Log.e( logTag+" response", String.format("%s%s\n%s%s\n%s\n%s",
                "Bad response code: ", responseCode,
                "Response Message: ", responseMessage,
                errorString,
                httpCon.getURL() )// End string.
        );


        httpCon.disconnect();
        return false;

    }



}
