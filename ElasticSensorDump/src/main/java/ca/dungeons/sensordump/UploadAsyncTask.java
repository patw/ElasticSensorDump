package ca.dungeons.sensordump;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A class to start a thread upload the database to Kibana.
 * @author Gurtok.
 * @version First version of upload Async thread.
 */
class UploadAsyncTask extends AsyncTask<Void,String,Void>{

    private DatabaseHelper dbHelper;
    private static ElasticSearchIndexer esIndexer = new ElasticSearchIndexer();
    private static Cursor cursor;
    private static JSONObject jsonObject;
    private Context callingContext;

    UploadAsyncTask( Context context  ) {
        dbHelper = new DatabaseHelper( context.getApplicationContext() );
        callingContext = context.getApplicationContext();
    }

    @Override
    protected void onPreExecute() {
        SQLiteDatabase readableDatabase = dbHelper.getReadableDatabase();
        cursor = readableDatabase.query("SELECT TOP 1 * FROM " + DatabaseHelper.TABLE_NAME +
                                      " ORDER BY RowID ACS",null,null,null,null,null,null, " 1");
    }

    /**
     * Check for internet connectivity.
     * If connected, start up the async thread to send.
     * @return Super requires a return value. In this case it will always be null.
      */
    @Override
    protected Void doInBackground(Void... params) {

        ConnectivityManager connectionManager = (ConnectivityManager)
                callingContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Boolean wifiCheck = connectionManager.getActiveNetworkInfo().isConnected();

        if( wifiCheck ) {
            do{
                String cursorString = cursor.getString( cursor.getColumnIndex(DatabaseHelper.dataColumn) );
                publishProgress( cursorString );
                dbHelper.deleteTopJson();
            }while( cursor.moveToNext() );

        }else{
            Log.e("No network connection", "No connection, saving to database");
        }

        return null;
    }

    /**
     * This is the work loop for uploading.
     * Called each loop of doInBackground method.
     * String array parameter contains only ONE value, stored at index[0].
     * @param values A string value to be converted to JSON.
     */
    @Override
    protected void onProgressUpdate(String... values) {

        try {
            jsonObject = new JSONObject( values[0] );
        } catch (JSONException e) {
            Log.v("error creating JSON", "Error creating JSON");
        }

        // if the json is not empty, send to kibana
        if (jsonObject.length() != 0) {
            esIndexer.index( jsonObject );
        }

    }

    /**
     * This runs after the dataBase is emptied or upload is interrupted.
     * @param Void Not used. Required parameter from super.
     */
    @Override
    protected void onPostExecute(Void Void) {
        dbHelper.close();
    }


}
