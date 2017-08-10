package ca.dungeons.sensordump;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
        if( connect("PUT") ) {

            String esType = "esd";

            try {
                DataOutputStream dataOutputStream = new DataOutputStream(httpCon.getOutputStream());
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
                dataOutputStream.writeBytes(mappings.toString());

                if (checkResponseCode()) {
                    UploadTask.indexSuccess(true);
                    Log.e(logTag + " newMap", "Mapping uploaded successfully. " + mappings.toString());
                } else {
                    Log.e(logTag + " newMap", "Failed response code check on MAPPING. " + mappings.toString());
                }

            } catch (JSONException j) {
                Log.e(logTag + " newMap", "JSON error: " + j.toString());
            } catch (IOException IoEx) {
                Log.e(logTag + " newMap", "Failed to write to outputStreamWriter.");
            }
        }else{
            Log.e(logTag + " newMap", "Failed to connect to elastic.");
        }
    }

        /** Send JSON data to elastic using POST. */
    private void index() {
        // POST our documents to elastic.
        if( connect("POST") ){

            try {
                DataOutputStream dataOutputStream = new DataOutputStream( httpCon.getOutputStream() );
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
    private boolean connect(String verb){

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
            httpCon.connect();
            if( httpCon.getDoInput() ){
                Log.e( logTag+" connect.", "Connected to ESD.");
                return true;
            }

        }catch(MalformedURLException urlEx){
            Log.e( logTag+" connect.", "Error building URL.");
        }catch (IOException IOex) {
            Log.e( logTag+" connect.", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
        }
        return false;
    }

    private boolean checkResponseCode(){
        String responseMessage = "ResponseCode placeholder.";
        int responseCode = 0;
        String errorString = "Error Message: ";
        String errorStreamMessage;

        BufferedReader errorStream;
        InputStream httpInputStream = httpCon.getErrorStream();

        InputStreamReader inputStreamReader;

        if( httpInputStream == null ){
            Log.e(logTag+" response", "Input stream is null." );
        }else{
            try{
                responseMessage = httpCon.getResponseMessage();
                responseCode = httpCon.getResponseCode();
                inputStreamReader = new InputStreamReader( httpInputStream );
                errorStream = new BufferedReader( inputStreamReader );
                while( ( errorStreamMessage = errorStream.readLine() ) != null ){
                    errorString = errorString + " : " + errorStreamMessage;
                }
                errorStream.close();

                if( 200 <= responseCode && responseCode <= 299 ){

                    httpCon.disconnect();
                    return true;
                }else{

                    if( responseCode != 0 ) {
                        throw new IOException("NO response");
                    }else{
                        throw new IOException("");
                    }
                }

            }catch( IOException ioEx ){

                if( ioEx.getMessage().equals( "NO response" ) ){
                    Log.e( logTag+" response", "Failed to retrieve response codes for REST operation." );
                }else{
                    // Something bad happened. I expect only the finest of 200's
                    Log.e( logTag+" response", String.format("%s%s\n%s%s\n%s\n%s",
                            "Bad response code: ", responseCode,
                            "Response Message: ", responseMessage,
                            errorString,
                            httpCon.getURL() )// End string.
                    );
                }

            }
        }

        httpCon.disconnect();
        return false;

    }



}
