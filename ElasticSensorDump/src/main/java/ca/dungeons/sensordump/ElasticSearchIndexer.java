package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.HashMap;

public class ElasticSearchIndexer {

    public boolean badUrl = false;
    public long failedIndex = 0;
    public long indexRequests = 0;
    public long indexSuccess = 0;
    public int lastResponseCode;
    private String esHost;
    private String esPort;
    private String esIndex;
    private String esType;
    private String esUsername;
    private String esPassword;
    private boolean esSSL;


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
                        lastResponseCode = responseCode;
                        failedIndex++;
                    }

                    httpCon.disconnect();
                } catch (Exception e) {
                    // Only show errors for index requests, not the mapping request
                    if (indexRequests != 0) {
                        Log.v("Fail Reason", e.toString());
                        Log.v("Fail URL", url);
                        Log.v("Fail Data", jsonData);
                        failedIndex++;
                    }
                }
                indexSuccess++;
            }
        };

        // Assuming the URL is valid, we can start
        if (!badUrl) {
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

    // Send JSON data to elastic using POST
    public void index(HashMap<String, Object> indexData) {
        // Create the mapping on first request
        if (indexRequests == 0) {
            createMapping();
        }
        String jsonData = new JSONObject(indexData).toString();
        String url = buildURL() + esType + "/";

        // If we have some data, it's good to post
        if (jsonData != null) {
            callElasticAPI("POST", url, jsonData);
        }
    }

}
