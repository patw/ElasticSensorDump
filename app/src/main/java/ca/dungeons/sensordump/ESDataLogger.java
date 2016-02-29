package ca.dungeons.sensordump;

import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Created by pwendorf on 2016-02-27.
 */
public class ESDataLogger {

    public int documents_written = 0;
    public int sync_errors = 0;
    private String es_url = null;
    private Handler post_handler;

    public ESDataLogger(String server, int port, String index, String type) {
        es_url = "http://" + server + ":" + port + "/" + index + "/" + type + "/";
    }

    public void store_hash(ArrayList<String> json_documents) {
        for (int i = 0; i < json_documents.size(); i++) {
            PostJSONDocsTask task = new PostJSONDocsTask();
            task.execute(json_documents.get(i));
            documents_written += 1;
            json_documents.remove(i);
            Log.v("Removed one.  New size", "" + json_documents.size());
        }
    }

    private class PostJSONDocsTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... json_docs) {
            try {
                URL url = new URL(es_url);
                HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
                httpCon.setDoOutput(true);
                httpCon.setRequestMethod("POST");
                OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());
                out.write(json_docs[0]);
                out.close();
                httpCon.getInputStream();
            } catch (Exception e) {
                Log.v(es_url, e.toString());
                sync_errors += 1;
            }
            return "Done!";
        }

        @Override
        protected void onPostExecute(String result) {
        }
    }
}