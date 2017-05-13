package ca.dungeons.sensordump;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;

/**
 * A class to start a thread upload the database to Kibana.
 * @author Gurtok.
 * @version First version of upload Async thread.
 */
class UploadAsyncTask extends AsyncTask<Void,Long,Void>{

    private DatabaseHelper dbHelper;
    static HttpURLConnection httpCon;
    static OutputStreamWriter outputStreamWriter;

    private Context passedContext;

    private boolean stopThread = false;

    /** Default Constructor using the application context. */
    UploadAsyncTask( Context context  ) {
        passedContext = context;
    }

    /** Required override method. Use to do initial housekeeping. */
    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        dbHelper = new DatabaseHelper( passedContext );
    }

    /**
     * Check for internet connectivity.
     * If connected, start up the async thread to send.
     * @return Super requires a return value. In this case it will always be null.
      */
    @Override
    protected Void doInBackground(Void... params) {
        SQLiteDatabase readableDatabase = dbHelper.getReadableDatabase();
        URL url = ElasticSearchIndexer.buildURL();
        boolean outputStreamStatus;

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
        // Try to establish a connection with Elastic server.
        try {
            httpCon = (HttpURLConnection) url.openConnection();
            httpCon.setConnectTimeout(2000);
            httpCon.setReadTimeout(2000);
            httpCon.setDoOutput(true);
            httpCon.setRequestMethod("PUT");
            outputStreamWriter = new OutputStreamWriter(httpCon.getOutputStream());
            outputStreamStatus = true;
        } catch (IOException IOex) {
            Log.e("Network Connection", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
            outputStreamStatus = false;
        }

        while(!stopThread ) {
            if (readableDatabase.getMaximumSize() >= 1 && outputStreamStatus) {
                String sqLiteQuery = "SELECT * FROM " + DatabaseHelper.TABLE_NAME + " ORDER BY ID ASC LIMIT 1";
                Cursor cursor = readableDatabase.rawQuery(sqLiteQuery, new String[]{});
                cursor.moveToFirst();
                do{
                    try{
                        JSONObject jsonObject = new JSONObject(cursor.getString(1));
                        if( jsonObject.length() != 0 && ElasticSearchIndexer.index(jsonObject) )
                            dbHelper.deleteTopJson();
                    }catch( JSONException e ){
                        Log.e( "error creating JSON", e.getMessage() + e.getCause() );
                    }
                } while( cursor.moveToNext() && !stopThread );

                cursor.close();
                long count = DatabaseUtils.queryNumEntries(readableDatabase, DatabaseHelper.TABLE_NAME, null);
                publishProgress(count);
            }
        }
    return null;
    }

    /**
     * This is the work loop for uploading.
     * Called each loop of doInBackground method.
     * String array parameter contains only ONE value, stored at index[0].
     * @param progress A string value to be converted to JSON.
     */
    @Override
    protected void onProgressUpdate(Long... progress) {
        super.onProgressUpdate(progress);

    }

    /**
     * This runs after the dataBase is emptied or upload is interrupted.
     * @param Void Not used. Required parameter from super.
     */
    @Override
    protected void onPostExecute(Void Void) {
        dbHelper.close();
        httpCon.disconnect();
    }

    @Override
    protected void onCancelled() {
        stopThread = true;
    }
}
