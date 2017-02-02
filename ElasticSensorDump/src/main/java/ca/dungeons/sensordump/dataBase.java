package ca.dungeons.sensordump;

// This class handles all the database activities
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.Cursor;
import android.content.Context;
import android.content.ContentValues;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

public class dataBase {

    public class dbInterface extends SQLiteOpenHelper{

        /**
         Created by Gurtok
         EDIT: 02-01-2017
         Version 1.0
         final NAME String: "dbStorage". Database should be persistent, even when empty.
         TABLE_NAME: passed in date string, already formatted.
         */

        private static final String DATABASE_NAME = "dbStorage"; // Main database name
        private static final int DATABASE_VERSION = 1; // database version
        private static final String TABLE_NAME = "StorageTable";// table name for database
        private String COLUMN_JSON = "json OBJ BLOB";


        public dbInterface(Context context, String name, SQLiteDatabase.CursorFactory factory, int version) {
            super(context, DATABASE_NAME, factory, DATABASE_VERSION);
        }
         // This should never run, will delete the database. Required method.
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
         /*
        * Create a DATABASE named as a static variable up top
        * two columns, one for KEY storage, one for the json blob
        * execute the query
         */
        @Override
        public void onCreate(SQLiteDatabase dataBase) {
            String query = "CREATE TABLE " + TABLE_NAME + "(" +
                            COLUMN_JSON + " BLOB " + " " +
                            "):";
            dataBase.execSQL(query);
        }
         /*
        * add a new json object to the table
        * the column ID will be auto-incremented
         */
        public void JsonToDatabase(JSONObject jsonObject){
            ContentValues values = new ContentValues();
            values.put(COLUMN_JSON, jsonObject.toString() );
            SQLiteDatabase db = getWritableDatabase();
            db.insert(TABLE_NAME, null, values);
            db.close();
        }
         /*
        * Delete a product from the database
         */
        public void deleteJson(String productName){
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("DELETE FROM " + TABLE_NAME + " WHERE " + COLUMN_JSON + "=\"" + productName + "\";");
        }
         /*
        * retrieve a string from the database
        * convert to json object
        * send to elastic via index function
         */
        public void SendJson() {
            JSONObject retrievedJson = new JSONObject();
            ElasticSearchIndexer esIndexer = new ElasticSearchIndexer();
            SQLiteDatabase mainDB = getReadableDatabase();

            Cursor recordCursor = mainDB.query("select top 1 * from" +
                    TABLE_NAME +
                    "order by RowID acs"
                    ,null,null,null,null,null,null,
                    "1");// LIMIT 1 json record at a time
            // get the string from DB
            String cursorString = recordCursor.getString(recordCursor.getColumnIndex(COLUMN_JSON));
            // try to convert string to a new json object
            try {
                retrievedJson = new JSONObject(cursorString);
            }catch(JSONException e){
                Log.v("error creating JSON", "Error creating JSON");
            }
            // if the json is not empty, send to kibana
            if( retrievedJson.length() != 0) {
                esIndexer.index(retrievedJson);
                mainDB.close();
                recordCursor.close();
            }else {
                mainDB.close();
                recordCursor.close();
            }

        }

    }
}