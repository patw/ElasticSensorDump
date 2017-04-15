package ca.dungeons.sensordump;

// This class handles all the database activities
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.Context;
import android.content.ContentValues;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.util.Log;
import org.json.JSONObject;

/**
 * A class to buffer generated data to a dataBase for later upload.
 * @author Gurtok.
 * @version First version of ESD dataBase helper.
 */
class DatabaseHelper extends SQLiteOpenHelper{

    /** Main database name */
    private static final String DATABASE_NAME = "dbStorage";

    /** Database version. */
    private static final int DATABASE_VERSION = 1;

    /** Table name for database. */
    static final String TABLE_NAME = "StorageTable";

    /** json text. */
    private static final String dataColumn = "JSON";

    private Context passedContext;

    /**
     * Default constructor. Creates a new dataBase if required.
     * @param context Calling method context.
     */
    DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        passedContext = context;
    }

    /**
     * @param db Existing dataBase.
     * @param oldVersion Old version number ID.
     * @param newVersion New version number ID.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
    }

    /**
     * Create the table for dataBase.
     * Two columns.
     * One for KEY storage.
     * One for the json text string.
     * Execute dataBase creation.
     * @param db Passed dataBase. Needs to be writable.
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        String query = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (ID INTEGER PRIMARY KEY, JSON TEXT);";
        db.execSQL( query );
    }

    /**
     * Pack the json object into a content values object for shipping.
     * Insert completed json object into dataBase.
     * Key will autoIncrement.
     * Will also start a background thread to upload the database to Kibana.
     * @param jsonObject Passed object to be inserted.
     */
    boolean JsonToDatabase(JSONObject jsonObject){

        ContentValues values = new ContentValues();
        ConnectivityManager connectionManager = (ConnectivityManager) passedContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectionManager.getActiveNetworkInfo();

        values.put(dataColumn, jsonObject.toString() );
        long checkDB = this.getWritableDatabase().insert( TABLE_NAME, null, values);

        //For testing purposes. REMOVE !
        if(checkDB == -1){
            Log.e("Failed insert","Failed insert database.");
            return false;
        }

        // Start the background upload task.
        if( networkInfo != null ){
            UploadAsyncTask backgroundThread = new UploadAsyncTask( passedContext );
            if( backgroundThread.getStatus() != AsyncTask.Status.RUNNING ){
                backgroundThread.execute();
            }
        }
    MainActivity.databaseEntries = DatabaseUtils.queryNumEntries(this.getReadableDatabase(), DatabaseHelper.TABLE_NAME, null );
    return true;
    }

    /** Delete top row from the database. */
    void deleteTopJson() {
        String sqlCommand = "DELETE FROM " + TABLE_NAME + " WHERE ID IN (SELECT ID FROM "
                + TABLE_NAME + " ORDER BY ID ASC LIMIT 1)";
        this.getWritableDatabase().execSQL(sqlCommand);
        Log.i("TOP deleted", String.format("%S", "The top index of StorageTable was deleted."));
    }

}
