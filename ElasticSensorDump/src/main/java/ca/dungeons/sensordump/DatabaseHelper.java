package ca.dungeons.sensordump;

// This class handles all the database activities
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.Context;
import android.content.ContentValues;
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
    static final String dataColumn = "JSON";

    /** Get a new instance of the background Async task. */
    private UploadAsyncTask backgroundThread;

    /**
     * Default constructor. Creates a new dataBase if required.
     * @param context Calling method context.
     */
    DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        backgroundThread = new UploadAsyncTask( context.getApplicationContext() );
    }

    /**
     * @param db Existing dataBase.
     * @param oldVersion Old version number ID.
     * @param newVersion New version number ID.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /**
     * Create the table for dataBase.
     * Two columns.
     * One for KEY storage.
     * One for the json text string.
     * Execute dataBase creation.
     * @param dataBase Passed dataBase. Needs to be writable.
     */
    @Override
    public void onCreate(SQLiteDatabase dataBase) {

        String query = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (ID INTEGER PRIMARY KEY, JSON TEXT);";
        dataBase.execSQL( query );
    }

    /**
     * Pack the json object into a content values object for shipping.
     * Insert completed json object into dataBase.
     * Key will autoIncrement.
     * Will also start a background thread to upload the database to Kibana.
     * @param jsonObject Passed object to be inserted.
     */
    void JsonToDatabase(JSONObject jsonObject){
        SQLiteDatabase storageWriteDatabase = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(dataColumn, jsonObject.toString() );
        long checkDB = storageWriteDatabase.insert( TABLE_NAME, null, values);

        // Start the background upload task.
        if( backgroundThread.getStatus() != AsyncTask.Status.RUNNING ){
            backgroundThread.execute();
        }
        //For testing purposes. REMOVE !
        if(checkDB == -1)
            Log.v("Failed insert","Failed insert database.");
        else
            Log.v("Inserted","inserted " + values);

    }

    /**
     * Delete top row from the database.
     */
    void deleteTopJson(){
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM " + TABLE_NAME + " WHERE " + dataColumn + "IN (SELECT _id FROM "
                + TABLE_NAME + " ORDER BY _id LIMIT 1)");
        Log.v("TOP deleted", String.format("%S%S%S","The top index of ", TABLE_NAME, " was delete."));
    }

}
