package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ElasticSearchIndexer{

    /** Elastic search web address. */
    private static String esHost, esPort, esIndex, esType;
    /** Elastic username. */
    static String esUsername = "";
    /** Elastic password. */
    static String esPassword = "";
    /** Represents if the server utilizes SSL security. */
    private static boolean esSSL;
    /** Reference to application preferences. */
    private static SharedPreferences sharedPreferences = MainActivity.sharedPrefs;
    /** True if we have already uploaded a mapping to Elastic. */
    private static boolean mappingCreated = false;

    /** Constructor: No parameters.
     */
    private ElasticSearchIndexer(){}

    /**
     * Extract config information from sharedPreferences.
     * Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
     */
    private static void updateURL() {
        esHost = sharedPreferences.getString("host", "localhost");
        esPort = sharedPreferences.getString("port", "9200");
        esIndex = sharedPreferences.getString("index", "customer");
        esType = sharedPreferences.getString("type", "phone_data");
        esSSL = sharedPreferences.getBoolean("ssl", false);
        esUsername = sharedPreferences.getString("user", "");
        esPassword = sharedPreferences.getString("pass", "");

        if ( sharedPreferences.getBoolean("index_date", false)) {
            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
            String dateString = logDateFormat.format(logDate);
            esIndex = esIndex + "-" + dateString;
        }

    }

    /** Build the URL based on the config data */
    static URL buildURL() {
        updateURL();
        URL tempOutput = null;

        String httpString = "http://";
        if (esSSL){
            httpString = "https://";
        }

        try{
            tempOutput = new URL( String.format("%s%s:%s/%s/",httpString ,esHost ,esPort ,esIndex ));
        }catch(MalformedURLException urlEx){
            Log.e("buildURL", "Error building URL.");
        }
        return tempOutput;
    }

    /** Create a map and send to elastic for sensor index. */
    private static void createMapping() {

        if( !mappingCreated ) {
            // Json object mappings first packet of data to upload to elastic.
            JSONObject mappings = new JSONObject();
            try {
                mappings.put("type", "geo_point");
                mappings.put("start_location", "typeGeoPoint");
                mappings.put("location", "typeGeoPoint");
                mappings.put("properties", "mappingTypes");
                mappings.put(esType, "properties");
                mappings.put("mappings", "indexType");
                mappingCreated = true;
                index(mappings);
            } catch (JSONException j) {
                Log.e("Mapping Error", "Error mapping " + j.toString());
                mappingCreated = false;
            }
        }
    }

    /**
     * Send JSON data to elastic using POST.
     * @param jsonObject The supplied json object to be uploaded.
     */
    static boolean index(JSONObject jsonObject) {

        // Create the mapping as the first index request per session.
        if ( !mappingCreated ){
            createMapping();
        }
        // If data exists, we are cleared to upload.
        if ( jsonObject != null && mappingCreated ) {
            try {
                UploadAsyncTask.outputStreamWriter.write( jsonObject.toString() );
                checkResponseCode();
            }catch(IOException IOex) {
                // Error writing to httpConnection.
                SensorThread.incrementUploadErrors();
                System.out.print("Failed to upload!!!");
                return false;
            }
        }
    return true;
    }

    /** Test to make sure the upload was successful.*/
    private static void checkResponseCode() throws IOException {
        UploadAsyncTask.httpCon.getInputStream();
        // Something bad happened. I expect only the finest of 200's
        int responseCode = UploadAsyncTask.httpCon.getResponseCode();
        if (200 <= responseCode && responseCode <= 299) {
            SensorThread.incrementDocumentsIndexed();
        }
    }


}
