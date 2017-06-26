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

    // /** Assigned tag for easy ID of documents.  */
    // private static String esTag;

    /** Elastic username. */
    private String esUsername = "";
    /** Elastic password. */
    private String esPassword = "";

    /** The "json" to be indexed. */
    private String uploadString;

    /** True if we have already uploaded a mapping to Elastic. */
    private boolean mappingCreated = false;
    private boolean putMapping = false;


    /** Used to establish outside connection. */
    private HttpURLConnection httpCon;
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
        }else{
            index();
        }
    }

    void setAuthorization( String userName, String password ){
        esUsername = userName;
        esPassword = password;
    }

    /** Create a map and send to elastic for sensor index. */
    private void createMapping() {
        // Json object mappings first packet of data to upload to elastic.
        // Each json is a container for similar data.
        if( !mappingCreated ) {
            String esType = "esd";

            connect("PUT");
            // Connect to elastic using PUT to make elastic understand this payload is a mapping.
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
                dataOutputStream.writeBytes(  mappings.toString());
                mappingCreated = true;
            } catch (JSONException j) {
                Log.e("ESI-Create Mapping", "JSON error: " + j.toString());
            }catch (IOException IoEx) {
                Log.e("ESI-Create Mapping", "Failed to write to outputStreamWriter." );
            }
        }
    }

        /** Send JSON data to elastic using POST. */
    private void index() {

        if( uploadString != null  ) {

            try {
                // POST our documents to elastic.
                connect("POST");
                if( outputStream != null ){
                    DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
                    //Log.e("ESI-INDEX", uploadString );
                    dataOutputStream.writeBytes( uploadString );
                    Log.e("ESI-INDEX", "Uploaded: " + uploadString );
                    // Check status of post operation.
                    if( checkResponseCode() ){
                        UploadTask.uploadSuccess();
                    }else{
                        UploadTask.uploadFailed();
                    }
                }else{
                    throw new IOException("OutputStream is null. Bad connection?" );
                }
            }catch( IOException IOex ){
                httpCon.disconnect();
                // Error writing to httpConnection.
                Log.e("ESI-Index", IOex.getMessage() );
            }
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
            if( httpCon == null ){
                Log.e("ESI-Connect", "Failed to connect with URL: " + elasticUrl );
                this.interrupt();
                return;
            }
            httpCon.setConnectTimeout(2000);
            httpCon.setReadTimeout(2000);
            httpCon.setDoOutput(true);
            httpCon.setRequestMethod(verb);
            httpCon.connect();
            outputStream = httpCon.getOutputStream();
        }catch(MalformedURLException urlEx){
            Log.e("ESI-Update URL", "Error building URL.");
        }catch (IOException IOex) {
            Log.e("ESI-Connect", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
        }

    }

    private boolean checkResponseCode(){
        String responseMessage = "responseMessage";
        int responseCode = 0;
        String errorString = "Error Message: ";
        String errorStreamMessage;

        BufferedReader errorStream = null;
        InputStream httpInputStream = httpCon.getErrorStream();
        InputStreamReader inputStreamReader;

        try{
            responseMessage = httpCon.getResponseMessage();
            responseCode = httpCon.getResponseCode();
            if( httpInputStream != null ){
                inputStreamReader = new InputStreamReader( httpInputStream );
                errorStream = new BufferedReader( inputStreamReader );
            }
            if( errorStream != null ){
                while( ( errorStreamMessage = errorStream.readLine() ) != null ){
                    errorString = errorString + "\n" + errorStreamMessage;
                }
                errorStream.close();
            }
        }catch( IOException ioEx ){
            Log.e("ESI-checkResponseCode", "Failed to retrieve response codes for REST operation." );
        }

        if( 200 <= responseCode && responseCode <= 299 ){
            httpCon.disconnect();
            return true;
        }else{
            // Something bad happened. I expect only the finest of 200's
            Log.e("ESI-checkResponseCode", String.format("%s%s\n%s%s\n%s",
                    "Bad response code: ", responseCode,
                    "Response Message: ", responseMessage,
                    errorString
            ));
        }
        httpCon.disconnect();
        return false;

    }



}
