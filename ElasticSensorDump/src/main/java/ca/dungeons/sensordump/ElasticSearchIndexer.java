package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class ElasticSearchIndexer {

    private static int MAX_FAILED_DOCS = 1000;
    private static int LAST_RESPONSE_CODE = 299;

    long failedIndex = 0;
    long indexRequests = 0;
    long indexSuccess = 0;
    private String esHost;
    private String esPort;
    private String esIndex;
    private String esTag;
    private String esType;
    private String esUsername;
    private String esPassword;
    private boolean esSSL;

    // We store all the failed index operations here, so we can replay them
    // at a later time.  This is to handle occasional disconnects in areas where
    // we may not have data or connection to the carrier network.
    private List<String> failedJSONDocs = new ArrayList<>();
    private boolean isLastIndexSuccessful = false;

    // Control variable to prevent sensors from being written before mapping created
    // Multi-threading is fun :(
    private boolean isCreatingMapping = true;

    // Another control variable, because threading is hate.
    private boolean isRetryingFailedIndexes = false;


    ElasticSearchIndexer() {
    }

    void updateURL(SharedPreferences sharedPrefs) {
        // Extract config information to build connection strings
        esHost = sharedPrefs.getString("host", "localhost");
        esPort = sharedPrefs.getString("port", "9200");
        esIndex = sharedPrefs.getString("index", "sensor_dump");
        esTag = sharedPrefs.getString("tag", "phone_data");
        esSSL = sharedPrefs.getBoolean("ssl", false);
        esUsername = sharedPrefs.getString("user", "");
        esPassword = sharedPrefs.getString("pass", "");

        // This was configurable in early versions.  With multiple types goign away in
        // Elastic 6.0, I've decided to make a single type and call it ESD.  Users
        // can now use the Tag option to create a string to constrain their data during
        // parallel ingests.
        esType = "esd";


        // Tag the current date stamp on the index name if set in preferences
        // Thanks GlenRSmith for this idea
        if (sharedPrefs.getBoolean("index_date", false)) {
            Date logDate = new Date(System.currentTimeMillis());
            SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd");
            String dateString = logDateFormat.format(logDate);
            esIndex = esIndex + "-" + dateString;
        }

    }

    // Stop/start should reset counters
    public void resetCounters() {
        failedIndex = 0;
        indexRequests = 0;
        indexSuccess = 0;
    }

    private void callElasticAPI(final String verb, final String url,
                                final String jsonData, final boolean isBulk) {
        indexRequests++;

        // Send authentication if required
        if (esUsername.length() > 0 && esPassword.length() > 0) {
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(esUsername, esPassword.toCharArray());
                }
            });
        }

        // Spin up a thread for http connection
        Runnable r = new Runnable() {
            public void run() {

                HttpURLConnection httpCon;
                OutputStreamWriter osw;
                URL u;

                try {
                    u = new URL(url);
                    httpCon = (HttpURLConnection) u.openConnection();
                    httpCon.setConnectTimeout(2000);
                    httpCon.setReadTimeout(2000);
                    httpCon.setDoOutput(true);
                    httpCon.setRequestMethod(verb);
                    osw = new OutputStreamWriter(httpCon.getOutputStream());
                    osw.write(jsonData);
                    osw.close();
                    httpCon.getInputStream();

                    // Something bad happened. I expect only the finest of 200's
                    int responseCode = httpCon.getResponseCode();
                    if (responseCode > LAST_RESPONSE_CODE) {
                        if (!isCreatingMapping) {
                            failedIndex++;
                            isLastIndexSuccessful = false;
                        }
                    } else {
                        isLastIndexSuccessful = true;
                        indexSuccess++;
                    }

                    httpCon.disconnect();

                } catch (Exception e) {

                    // Probably a connection error.  Maybe.  Lets just buffer up the json
                    // docs so we can try them again later
                    if (e instanceof IOException) {
                        if (!isCreatingMapping && !isBulk) {
                            isLastIndexSuccessful = false;
                            // Store up to MAX_FAILED_DOCS worth of data before dumping it
                            if(failedJSONDocs.size() < MAX_FAILED_DOCS) {
                                failedJSONDocs.add(jsonData);
                            }
                        }
                    }

                    // Only show errors for index requests, not the mapping request
                    if (isCreatingMapping) {
                        isCreatingMapping = false;
                    } else {
                        Log.v("Index Request", "" + indexRequests);
                        Log.v("Fail Reason", e.toString());
                        Log.v("Fail URL", url);
                        Log.v("Fail Data", jsonData);
                        failedIndex++;
                    }
                }
                // We are no longer creating the mapping.  Time for sensor readings!
                if (isCreatingMapping) {
                    isCreatingMapping = false;
                }

                // Bulk success!
                if (isBulk) {
                    // Clear failed log and update our stats
                    failedIndex -= failedJSONDocs.size();
                    indexSuccess += failedJSONDocs.size();
                    failedJSONDocs.clear();
                    isRetryingFailedIndexes = false;
                }
            }
        };

        // Only allow posts if we're not creating mapping
        if (isCreatingMapping) {
            if (verb.equals("PUT")) {
                Thread t = new Thread(r);
                t.start();
            }
        } else {
            // We're not creating a mapping, just go nuts
            Thread t = new Thread(r);
            t.start();
        }
    }

    // Build the URL based on the config data
    private String buildURL() {
        if (esSSL) {
            return "https://" + esHost + ":" + esPort + "/" + esIndex + "/";
        } else {
            return "http://" + esHost + ":" + esPort + "/" + esIndex + "/";
        }
    }

    // Send mapping to elastic for sensor index using PUT
    // I'm sorry this is ugly.
    private void createMapping() {
        String es_mapping = "{\"mappings\": {\"esd\": {\"dynamic_templates\": [{\"long_to_float\": {\"match_mapping_type\": \"long\",\"mapping\": {\"type\": \"float\"}}}],\"properties\":{\"start_location\":{\"type\":\"geo_point\"},\"location\":{\"type\":\"geo_point\"},\"tag\":{\"type\":\"keyword\"},\"gps_provider\":{\"type\":\"keyword\"}}}}}";
        Log.v("Mapping", es_mapping);
        callElasticAPI("PUT", buildURL(), es_mapping, false);
    }

    // Spam those failed docs!
    // Maybe this should be a bulk operation... one day
    private void indexFailedDocuments() {
        String url;
        StringBuilder bulkDataList = new StringBuilder();

        // Bulk index url
        if (esSSL) {
            url = "https://" + esHost + ":" + esPort + "/_bulk";
        } else {
            url = "http://" + esHost + ":" + esPort + "/_bulk";
        }

        for (String failedJsonDoc : failedJSONDocs) {
            bulkDataList.append("{\"index\":{\"_index\":\"").append(esIndex)
                    .append("\",\"_type\":\"").append(esType).append("\"}}\n");
            bulkDataList.append(failedJsonDoc).append("\n");
        }

        String bulkData = bulkDataList.toString();

        Log.v("Bulk Data", bulkData);
        callElasticAPI("POST", url, bulkData, true);
    }

    // Send JSON data to elastic using POST
    void index(JSONObject joIndex) {

        // Create the mapping on first request
        if (isCreatingMapping && indexRequests == 0) {
            createMapping();
        }

        // Before indexing, make sure the user tag is in the document if they've made one
        try {
            if(!esTag.equals("")) {
                joIndex.put("tag", esTag);
            }
        } catch (Exception e) {
            Log.v("JSON Logging error", e.toString());
        }

        String jsonData = joIndex.toString();
        String url = buildURL() + esType + "/";

        // If we have some data, it's good to post
        if (jsonData != null) {
            callElasticAPI("POST", url, jsonData, false);
        }

        // Try it again!
        if (isLastIndexSuccessful && failedJSONDocs.size() > 0 && !isRetryingFailedIndexes) {
            isRetryingFailedIndexes = true;
            indexFailedDocuments();
        }
    }

}
