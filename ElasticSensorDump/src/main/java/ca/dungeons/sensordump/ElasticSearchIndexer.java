package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
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
    private void updateURL() {

        String esHost = sharedPreferences.getString("host", "localhost");
        String esPort = sharedPreferences.getString("port", "9200");
        esIndex = sharedPreferences.getString("index", "test_index");
        esType = sharedPreferences.getString("type", "phone_data");
        boolean esSSL = sharedPreferences.getBoolean("ssl", false);
        esUsername = sharedPreferences.getString("user", "");
        esPassword = sharedPreferences.getString("pass", "");

        if ( sharedPreferences.getBoolean("index_date", false) ){
            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
            String dateString = logDateFormat.format(logDate);
            esIndex = esIndex + "-" + dateString;
        }

        String httpString = "http://";
        if( esSSL )
            httpString = "https://";

        try{
            elasticURL = new URL( String.format( "%s%s:%s/%s/", httpString ,esHost ,esPort ,esIndex ));
        }catch(MalformedURLException urlEx){
            Log.e("ElasticSearchIndexer", "Error building URL.");
        }

    }


        /** Create a map and send to elastic for sensor index. */
    private void createMapping() {
            // Json object mappings first packet of data to upload to elastic.
            JSONObject mapping = new JSONObject();
            try {
                mapping.put("mappings", "indexType");
                mapping.put(esType, "properties");
                mapping.put("properties", "mappingTypes");
                mapping.put("type", "geo_point");
                mapping.put("start_location", "typeGeoPoint");
                mapping.put("location", "typeGeoPoint");

                httpCon.setRequestMethod("PUT");
                Log.e("ESI", "Mapping: " + mapping.toString() );
                outputStream.write( mapping.toString() );
                // Something bad happened. I expect only the finest of 200's
                int responseCode = httpCon.getResponseCode();
                if( 200 <= responseCode && responseCode <= 299 ){
                    Log.e("ESI", "Mapping successful." );
                }else{
                    Log.e("ESI", "Bad response code: " + responseCode );
                    throw new IOException();
                }
                httpCon.setRequestMethod("POST");
                mappingCreated = true;
            }catch( JSONException j ){
                Log.e("ElasticSearchIndexer", "ESI Error: " + j.toString() );
                mappingCreated = false;
            }catch( IOException IoEx ){
                Log.e("ElasticSearchIndexer", "IO error " + IoEx.getMessage() );
                mappingCreated = false;
            }
    }

        /** Send JSON data to elastic using POST.
         *  @param jsonObject The supplied json object to be uploaded.
         */
    boolean index(JSONObject jsonObject) {

        // Create the mapping as the first index request per session.
        if ( !mappingCreated ){
            createMapping();
        }else if( jsonObject != null ) {
            try {
                outputStream.write( jsonObject.toString() );
                // Something bad happened. I expect only the finest of 200's
                int responseCode = httpCon.getResponseCode();
                if( 200 <= responseCode && responseCode <= 299 ){
                    return true;
                }else{
                    throw new IOException();
                }
            }catch(IOException IOex) {
                // Error writing to httpConnection.
                System.out.print("Failed to upload!!!");
                return false;
            }
        }
    return true;
    }


    /** Open a connection with the server. */
    void connect(){
        updateURL();

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
        // Get connection, then upload our initial index name.
        try {
            httpCon = (HttpURLConnection) elasticURL.openConnection();
            httpCon.setConnectTimeout(2000);
            httpCon.setReadTimeout(2000);
            httpCon.setDoOutput(true);
            outputStream = new OutputStreamWriter( httpCon.getOutputStream() );
            Log.e( "ESI", "Successful connection to elastic." );
        } catch (IOException IOex) {
            Log.e("UploadTask", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
        }

    }

    /** Close our resources. */
    void disconnect(){
        try {
            // When above while loop breaks, we need to close out our resources.
            outputStream.close();
            httpCon.disconnect();
        }catch( IOException IoEx){
            Log.e("UploadTask", "Error closing out stream writer." );
        }catch(NullPointerException NullEx ){
            Log.e("UploadTask", "The connection failed to close, possible null ptr.");
        }

    }


}
