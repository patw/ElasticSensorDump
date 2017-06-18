package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ElasticSearchIndexer{

        /** Elastic search web address. */
    private static String esType, esIndex;
        /** Elastic username. */
    private static String esUsername = "";
        /** Elastic password. */
    private static String esPassword = "";

    private URL elasticURL;
        /** Reference to application preferences. */
    private SharedPreferences sharedPreferences;

        /** True if we have already uploaded a mapping to Elastic. */
    private static boolean mappingCreated = false;
        /** Our connection to the outside world. */
    private static OutputStreamWriter outputStream;
        /** Used to establish outside connection. */
    private static HttpURLConnection httpCon;

        /** Base constructor. */
    ElasticSearchIndexer(SharedPreferences passedPreferences ){
        sharedPreferences = passedPreferences;

    }

        /** Extract config information from sharedPreferences.
         *  Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
         */
    private void updateURL(String verb) {

        boolean esSSL = sharedPreferences.getBoolean("ssl", false);
        esUsername = sharedPreferences.getString("user", "");
        esPassword = sharedPreferences.getString("pass", "");


        String esHost = sharedPreferences.getString("host", "localhost");
        String esPort = sharedPreferences.getString("port", "9200");
        esIndex = sharedPreferences.getString("index", "test_index");
        esType = sharedPreferences.getString("type", "phone_data");


        if ( sharedPreferences.getBoolean("index_date", false) ){
            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
            String dateString = logDateFormat.format(logDate);
            esIndex = esIndex + "-" + dateString;
        }

        String httpString = "http://";
        if( esSSL )
            httpString = "https://";

        String urlString = String.format( "%s%s:%s/%s/", httpString ,esHost ,esPort ,esIndex );

        if( verb.equals("POST") ){
            urlString = urlString + esType + "/";
        }

        try{
            elasticURL = new URL( urlString );
            //Log.e("ElasticSearchIndexer", elasticURL.toString() );
        }catch(MalformedURLException urlEx){
            Log.e("ESI-Update URL", "Error building URL.");
        }

    }


        /** Create a map and send to elastic for sensor index. */
    private void createMapping() {
        // Json object mappings first packet of data to upload to elastic.
        // Each json is a container for similar data.
        if( !mappingCreated ) {
            // Connect to elastic using PUT to make elastic understand this payload is a mapping.
            connect("PUT");
            try {
                // Our main container for explicit typing of fields.
                JSONObject mappingTypes = new JSONObject();
                // Explicit typing of gps coordinates.
                JSONObject typeGeoPoint = new JSONObject().put("type", "geo_point");
                // To put start_location and location under geo_point type.
                mappingTypes.put("start_location", typeGeoPoint);
                mappingTypes.put("location", typeGeoPoint);
                // Put our new geo_point type under properties field.
                JSONObject properties = new JSONObject().put("properties", mappingTypes);
                // This specifies the index type.
                JSONObject indexType = new JSONObject().put(esType, properties);
                // Finally we take this nested json and specify that we want to use this as mapping.
                JSONObject mappings = new JSONObject().put("mappings", indexType);

                // Write out to elastic using the passed outputStream that is connected.
                if( outputStream != null )
                    outputStream.write(mappings.toString());

                String responseMessage = httpCon.getResponseMessage();
                int responseCode = httpCon.getResponseCode();
                String responseString = httpCon.getURL().toString();

                if (200 <= responseCode && responseCode <= 299) {
                    Log.e("ESI-Create Mapping", "Mapping successful.");
                    disconnect();
                } else {
                    // Something bad happened. I expect only the finest of 200's
                    Log.e("ESI-Create Mapping", String.format("%s%s\n%s%s\n%s%s\n%s%s",
                            "Bad response code: ", responseCode,
                            "Response Message: ", responseMessage,
                            "Failed mapping: ", mappings.toString(),
                            "URL: ", responseString
                    ));

                    BufferedReader errorStream = new BufferedReader(new InputStreamReader(httpCon.getErrorStream() ) );
                    String errorMessage;
                    while( ( errorMessage = errorStream.readLine() ) != null ){
                        Log.e("ESI-Create Mapping", errorMessage );
                    }

                    errorStream.close();
                    disconnect();
                    throw new IOException();
                }
                mappingCreated = true;
            } catch (JSONException j) {
                Log.e("ESI-Create Mapping", "JSON error: " + j.toString());
                mappingCreated = false;
                disconnect();
            } catch (IOException IoEx) {
                IoEx.printStackTrace();
                mappingCreated = false;
                disconnect();
            }
        }
    }

        /** Send JSON data to elastic using POST.
         *  @param jsonObject The supplied json object to be uploaded.
         */
    boolean index(JSONObject jsonObject) {

        createMapping();

        if( jsonObject != null ) {
            try {
                // Connect to elastic using POST.
                connect("POST");
                if( outputStream != null ){
                    outputStream.write(jsonObject.toString());
                }

                int responseCode = httpCon.getResponseCode();
                if( 200 <= responseCode && responseCode <= 299 ){
                    return true;
                }else{
                    // Something bad happened. I expect only the finest of 200's.
                    BufferedReader errorStream = new BufferedReader(new InputStreamReader(httpCon.getErrorStream() ) );
                    String errorMessage;
                    while( ( errorMessage = errorStream.readLine() ) != null ){
                        Log.e("ESI-Index", errorMessage );
                    }
                    throw new IOException( responseCode + "" );
                }
            }catch( IOException IOex ){
                // Error writing to httpConnection.
                Log.e("ESI-Index", IOex.getMessage() );
                return false;
            }
        }
    return true;
    }


    /** Open a connection with the server. */
    private boolean connect(String verb){
        updateURL(verb);

        // Set default authenticator if required
        final String elasticUserName = ElasticSearchIndexer.esUsername;
        final String elasticPassword = ElasticSearchIndexer.esPassword;
        if (elasticUserName.length() > 0 && elasticPassword.length() > 0) {
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(elasticUserName, elasticPassword.toCharArray());
                }
            });
        }
        // Get connection.
        try {
            httpCon = (HttpURLConnection) elasticURL.openConnection();
            httpCon.setConnectTimeout(2000);
            httpCon.setReadTimeout(2000);
            httpCon.setRequestMethod(verb);
            httpCon.setDoOutput(true);
            outputStream = new OutputStreamWriter( httpCon.getOutputStream() );
            Log.e( "ESI-Connect", "Successful connection to elastic using verb: " + verb );

        } catch (IOException IOex) {
            Log.e("ESI-Connect", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
            return false;
        }
        return true;
    }

    /** Close our resources. */
    void disconnect(){
        //httpCon.disconnect();
        try {
            // When above while loop breaks, we need to close out our resources.
            if( outputStream != null )
                outputStream.close();
                outputStream = null;
        }catch( IOException IoEx){

            //Log.e("ESI-Disconnect", "Error closing out stream writer. " + IoEx.getCause() + IoEx.getMessage() );
        }catch( NullPointerException NullEx ){
            Log.e("ESI-Disconnect", "The connection failed to close, possible null ptr.");
        }
    }


}
