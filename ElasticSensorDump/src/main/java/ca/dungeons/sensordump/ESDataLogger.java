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

    public int documents_written = 0;
    public int sync_errors = 0;
    public String es_host = "";
    public String es_port = "";
    public String es_index = "";
    public String es_type = "";
    public boolean es_ssl = false;
    public String es_user = "";
    public String es_pass = "";
    private Handler post_handler;

    public ESDataLogger() {
        // Do nothing constructor
    }

    // Return the new URL
    private String get_es_url() {
        String es_proto = "http://";

        // For Shield
        if (es_ssl) {
            es_proto = "https://";
        }

        return es_proto + es_host + ":" + es_port + "/" + es_index + "/" + es_type + "/";
    }

    public void store_hash(ArrayList<String> json_documents) {
        for (int i = 0; i < json_documents.size(); i++) {
            PostJSONDocsTask task = new PostJSONDocsTask();
            task.execute(json_documents.get(i));
            documents_written += 1;
            json_documents.remove(i);
        }
    }

    private class PostJSONDocsTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... json_docs) {

            // Send authentication if required
            if (es_user.length() > 0 && es_pass.length() > 0) {
                Authenticator.setDefault(new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(es_user, es_pass.toCharArray());
                    }
                });
            }

            try {
                URL url = new URL(get_es_url());
                HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
                httpCon.setDoOutput(true);
                httpCon.setRequestMethod("POST");
                OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());
                out.write(json_docs[0]);
                out.close();
                httpCon.getInputStream();
            } catch (Exception e) {
                Log.v(get_es_url(), e.toString());
                sync_errors += 1;
            }
            return "Done!";
        }

        @Override
        protected void onPostExecute(String result) {
        }
    }
}