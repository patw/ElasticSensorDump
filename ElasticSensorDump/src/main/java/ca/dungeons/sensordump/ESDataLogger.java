package ca.dungeons.sensordump;

import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;

import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.ArrayList;

/**
 * Created by pwendorf on 2016-02-27.
 */
public class ESDataLogger {

    public int documentsWritten = 0;
    public int syncErrors = 0;
    public String esHost = "";
    public String esPort = "";
    public String esIndex = "";
    public String esType = "";
    public boolean esSSL = false;
    public String esUsername = "";
    public String esPassword = "";

    private Handler postHandler;

    public ESDataLogger() {
        // Do nothing constructor
    }

    // Return the new URL
    private String getEsUrl() {
        String esProto = "http://";

        // For Shield
        if (esSSL) {
            esProto = "https://";
        }

        return esProto + esHost + ":" + esPort + "/" + esIndex + "/" + esType + "/";
    }

    public void storeHash(ArrayList<String> jsonDocuments) {
        for (int i = 0; i < jsonDocuments.size(); i++) {
            PostJSONDocsTask task = new PostJSONDocsTask();
            task.execute(jsonDocuments.get(i));
            documentsWritten += 1;
            jsonDocuments.remove(i);
        }
    }

    private class PostJSONDocsTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... jsonDocs) {

            // Send authentication if required
            if (esUsername.length() > 0 && esPassword.length() > 0) {
                Authenticator.setDefault(new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(esUsername, esPassword.toCharArray());
                    }
                });
            }

            try {
                URL url = new URL(getEsUrl());
                HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
                httpCon.setDoOutput(true);
                httpCon.setRequestMethod("POST");
                OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());
                out.write(jsonDocs[0]);
                out.close();
                httpCon.getInputStream();
            } catch (Exception e) {
                Log.v(getEsUrl(), e.toString());
                syncErrors += 1;
            }
            return "Done!";
        }

        @Override
        protected void onPostExecute(String result) {
        }
    }
}