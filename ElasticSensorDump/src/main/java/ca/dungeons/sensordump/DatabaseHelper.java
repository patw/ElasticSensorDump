package ca.dungeons.sensordump;

import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.Context;
import android.content.ContentValues;
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
        /** Json data column name. */
    private static final String dataColumn = "JSON";
        /** Since we only have one database, we reference it on creation. */
    private SQLiteDatabase database = this.getReadableDatabase();


        /** Default constructor.
         *  Creates a new dataBase if required.
         *  @param context Calling method context.
         */
    DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        String query = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (ID INTEGER PRIMARY KEY, JSON TEXT);";
        database.execSQL( query );
    }

        /** Get number of database entries.*/
    long databaseEntries(){
        return DatabaseUtils.queryNumEntries( database, DatabaseHelper.TABLE_NAME, null );
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

        /** Required over-ride method. Not currently used.*/
    @Override
    public void onCreate(SQLiteDatabase db) {
    }

        /**
         * Pack the json object into a content values object for shipping.
         * Insert completed json object into dataBase.
         * Key will autoIncrement.
         * Will also start a background thread to upload the database to Kibana.
         * @param jsonObject Passed object to be inserted.
         */
    boolean JsonToDatabase(JSONObject jsonObject){

        SQLiteDatabase writableDatabase = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(dataColumn, jsonObject.toString() );
        long checkDB = writableDatabase.insert( TABLE_NAME, null, values);

        if(checkDB == -1){
            Log.e("Failed insert","Failed insert database.");
            return false;
        }

        writableDatabase.close();
        return true;
    }

        /** Delete top row from the database. */
    void deleteTopJson() {
        SQLiteDatabase writableDatabase = this.getWritableDatabase();
        String sqlCommand = "DELETE FROM " + TABLE_NAME + " WHERE ID IN (SELECT ID FROM "
                + TABLE_NAME + " ORDER BY ID ASC LIMIT 1)";
        writableDatabase.execSQL(sqlCommand);
        Log.i( "TOP deleted", String.format("%S", "The top index of StorageTable was deleted.") );
        writableDatabase.close();
    }

}
