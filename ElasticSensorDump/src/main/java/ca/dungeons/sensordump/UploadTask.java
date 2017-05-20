package ca.dungeons.sensordump;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * A class to start a thread upload the database to Kibana.
 * @author Gurtok.
 * @version First version of upload Async thread.
 */
class UploadTask extends AsyncTask< Void, Void, Void>{

    /** Used to identify the origin of the message sent to the UI thread. */
    static final int UPLOAD_TASK_ID = 123456;
    /** Used to gain access to the application database. */
    private Context passedContext;

    private SharedPreferences sharedPreferences;

    private DatabaseHelper dbHelper;


    /** Reference handler to send messages back to the UI thread. */
    private Handler uiHandler;

    /** Number of documents sent to server this session, default 0. */
    private int documentsIndexed = 0;
    /** Number of failed upload transactions this session, default 0. */
    private int uploadErrors = 0;

    /** Default Constructor using the application context. */
    UploadTask(Context context, Handler handler, SharedPreferences passedPreferences ) {
        passedContext = context;
        uiHandler = handler;
        sharedPreferences = passedPreferences;
    }

    /** Required override method. Use to do initial housekeeping. */
    @Override
    protected void onPreExecute() {
        dbHelper = new DatabaseHelper( passedContext );
    }

    /**
     * Check for internet connectivity.
     * If connected, start up the async thread to send.
     * @return Super requires a return value. In this case it will always be null.
      */
    @Override
    protected Void doInBackground( Void... params) {

        ElasticSearchIndexer esIndexer = new ElasticSearchIndexer( sharedPreferences );
        String sqLiteQuery = "SELECT * FROM " + DatabaseHelper.TABLE_NAME + " ORDER BY ID ASC LIMIT 1";
        SQLiteDatabase readableDatabase = dbHelper.getReadableDatabase();
        Cursor cursor = readableDatabase.rawQuery(sqLiteQuery, new String[]{});
        JSONObject jsonObject;

        esIndexer.connect();

        long lastTime = System.currentTimeMillis();

        // Loop to keep uploading at a limit of 4 outs per second, while the main thread doesn't cancel.
        while( !this.isCancelled()  ) {

            if( System.currentTimeMillis() > (lastTime + 250) ) {

                while( cursor.moveToNext() ) {
                    try {
                        jsonObject = new JSONObject( cursor.getString(1) );
                        Log.e("UploadTest", "JsonObject = " + jsonObject.toString() );
                        // If the json has data; if the upload to elastic succeeded.
                        if (jsonObject.length() != 0 && esIndexer.index(jsonObject)) {
                            documentsIndexed++;
                            dbHelper.deleteTopJson();
                        } else {
                            throw new JSONException("");
                        }
                    } catch (JSONException JsonEx) {
                        uploadErrors++;
                        Log.e("UploadTask", "Error creating json.");
                    }
                }

                Message outMessage = uiHandler.obtainMessage();
                outMessage.what = UPLOAD_TASK_ID;
                Bundle dataBundle = new Bundle(2);
                dataBundle.putLong("documentsIndexed", documentsIndexed);
                dataBundle.putLong("uploadErrors", uploadErrors);

                outMessage.setData(dataBundle);
                uiHandler.sendMessage(outMessage);
                lastTime = System.currentTimeMillis();
            }
        }
        esIndexer.disconnect();
        cursor.close();
    return null;
    }

    /**
     * This is the work loop for uploading.
     * Called each loop of doInBackground method.
     * String array parameter contains only ONE value, stored at index[0].
     * @param progress A string value to be converted to JSON.
     */
    @Override
    protected void onProgressUpdate(Void... progress){}

    /**
     * This runs after the dataBase is emptied or upload is interrupted.
     * @param Void Not used. Required.
     */
    @Override
    protected void onPostExecute(Void Void) {
    }

    @Override
    protected void onCancelled() {
    }

}
