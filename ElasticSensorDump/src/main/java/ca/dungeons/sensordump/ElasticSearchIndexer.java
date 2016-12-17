package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ElasticSearchIndexer {

    public long failedIndex = 0;
    public long indexRequests = 0;
    public long indexSuccess = 0;
    private String esHost;
    private String esPort;
    private String esIndex;
    private String esType;
    private String esUsername;
    private String esPassword;
    private boolean esSSL;

    // We store all the failed index operations here, so we can replay them
    // at a later time.  This is to handle occasional disconnects in areas where
    // we may not have data or connection to the carrier network.
    private List<String> failedJSONDocs = new ArrayList<String>();
    private boolean isLastIndexSuccessful = false;

    // Control variable to prevent sensors from being written before mapping created
    // Multi-threading is fun :(
    private boolean isCreatingMapping = true;


    public ElasticSearchIndexer() {
    }

    public void updateURL(SharedPreferences sharedPrefs) {
        // Extract config information to build connection strings
        esHost = sharedPrefs.getString("host", "localhost");
        esPort = sharedPrefs.getString("port", "9200");
        esIndex = sharedPrefs.getString("index", "sensor_dump");
        esType = sharedPrefs.getString("type", "phone_data");
        esSSL = sharedPrefs.getBoolean("ssl", false);
        esUsername = sharedPrefs.getString("user", "");
        esPassword = sharedPrefs.getString("pass", "");
    }

    // Stop/start should reset counters
    public void resetCounters() {
        failedIndex = 0;
        indexRequests = 0;
        indexSuccess = 0;
    }

    private void callElasticAPI(final String verb, final String url, final String jsonData) {
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
                    if (responseCode > 299) {
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
                        if (!isCreatingMapping) {
                            failedJSONDocs.add(jsonData);
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
    private void createMapping() {
        String mappingData = "{\"mappings\":{\"" + esType + "\":{\"properties\":{\"location\":{\"type\": \"geo_point\"},\"start_location\":{\"type\":\"geo_point\"}}}}}";
        String url = buildURL();
        callElasticAPI("PUT", url, mappingData);
    }

    // Spam those failed docs!
    // Maybe this should be a bulk operation... one day
    private void indexFailedDocuments() {
        String url = buildURL() + esType + "/";

        for (String failedJsonDoc : failedJSONDocs) {
            callElasticAPI("POST", url, failedJsonDoc);
        }

        // Update the metrics to show how awesome we are
        failedIndex = failedIndex - failedJSONDocs.size();
        indexSuccess = indexSuccess + failedJSONDocs.size();
        failedJSONDocs.clear();
    }

    // Send JSON data to elastic using POST
    public void index(JSONObject joIndex) {

        // Create the mapping on first request
        if (isCreatingMapping && indexRequests == 0) {
            createMapping();
        }

        String jsonData = joIndex.toString();
        String url = buildURL() + esType + "/";

        // If we have some data, it's good to post
        if (jsonData != null) {
            callElasticAPI("POST", url, jsonData);
        }

        // Try it again!
        if (isLastIndexSuccessful && failedJSONDocs.size() > 0) {
            indexFailedDocuments();
        }
    }

}
