package ca.dungeons.sensordump;

import android.database.Cursor;
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
    private static final String TABLE_NAME = "StorageTable";

        /** Json data column name. */
    private static final String dataColumn = "JSON";

        /** Since we only have one database, we reference it on creation. */
    private final SQLiteDatabase writableDatabase = this.getWritableDatabase();

        /** Used to keep track of the database row we are working on. */
    private int deleteRowId;

        /** Default constructor.
         *  Creates a new dataBase if required.
         *  @param context Calling method context.
         */
    DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        String query = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (ID INTEGER PRIMARY KEY, JSON TEXT);";
        writableDatabase.execSQL( query );
    }

        /** Get number of database entries.*/
    long databaseEntries(){
        return DatabaseUtils.queryNumEntries( writableDatabase, DatabaseHelper.TABLE_NAME, null );
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
    void JsonToDatabase(JSONObject jsonObject){
        ContentValues values = new ContentValues();
        values.put(dataColumn, jsonObject.toString() );
        long checkDB = writableDatabase.insert( TABLE_NAME, null, values);

        if(checkDB == -1){
            Log.e("Failed insert","Failed insert database.");

        }

    }

        /** Delete top row from the database. */
    void deleteJson() {
        writableDatabase.execSQL( "DELETE FROM " + TABLE_NAME + " WHERE ID = " + deleteRowId );
    }

        /** Query the database for the next row. Return null if database is empty. */
    String getNextCursor(){

        if( databaseEntries() >= 1 ){
            Cursor outCursor = writableDatabase.rawQuery( "SELECT * FROM " + DatabaseHelper.TABLE_NAME + " ORDER BY ID ASC LIMIT 1", new String[]{} );
            outCursor.moveToFirst();
            deleteRowId = outCursor.getInt( 0 );
            String deleteRowString = outCursor.getString(1);
            outCursor.close();
            return deleteRowString;
        }
        return null;
    }

}
