package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.util.Log;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 Created by Gurtok on 10/8/2017. */

class UploadRunnable implements Runnable {

  /** Static variable for the indexer thread to communicate success or failure of an index attempt. */
  private boolean uploadSuccess = false;

  private boolean mapSuccess = false;

  private DatabaseHelper dbHelper;

  /** Control variable to indicate if we should stop uploading to elastic. */
  private static boolean stopUploadThread = false;
  /** ID for logcat. */
  private final String logTag = "UploadRunnable";

  /** A reference to the apps stored preferences. */
  private final SharedPreferences sharedPreferences;

  private Thread indexerThread;

  /** Control variable to indicate if this runnable is currently uploading data. */
  private boolean working = false;

  /** Used to keep track of how many POST requests we are allowed to do each second. */
  private Long globalUploadTimer = System.currentTimeMillis();

  private String esIndex;
  private String esType;

  private ElasticSearchIndexer esIndexer;

  private ElasticMapper elasticMapper;

  private EsdServiceManager serviceManager;

  /** */
  UploadRunnable(SharedPreferences sharedPreferences, DatabaseHelper dbHelper, EsdServiceManager serviceManager) {
    this.sharedPreferences = sharedPreferences;
    this.dbHelper = dbHelper;
    this.serviceManager = serviceManager;

    esIndexer = new ElasticSearchIndexer( this );
    elasticMapper = new ElasticMapper( this );

  }

  /** */
  @Override
  public void run() {
    startUpload();

  }

  /** Control variable to halt the whole thread. */
  private void stopUploading() {
    if( dbHelper != null ){
      dbHelper.close();
    }
    working = false;
    stopUploadThread = true;
  }

  /** Used by the service manager to indicate if this runnable is uploading data. */
  synchronized boolean isWorking() {
    return working;
  }

  private void createMapping(){
    elasticMapper.mapUrl = updateUrl( "MAP" );
    Thread mapThread = new Thread( elasticMapper );
    mapThread.start();
    try {
      mapThread.join();
    } catch (InterruptedException interruptEx) {
      interruptEx.printStackTrace();
    }
  }

  /** Main work of upload runnable is accomplished here. */
  private void startUpload() {
    Log.e(logTag, "Started upload thread.");

    working = true;
    stopUploadThread = false;
    indexerThread = new Thread( esIndexer );
    int timeOutCount = 0;

    updateAuthentication();

    if( !mapSuccess ){
      createMapping();
    }
      /* Loop to keep uploading. */
      while (!stopUploadThread) {
        if( System.currentTimeMillis() > globalUploadTimer + 200 ){
          globalUploadTimer = System.currentTimeMillis();
          Log.e( logTag, "Upload loop" );
          esIndexer.postUrl = updateUrl("POST");

          if( uploadIndex() ){
            timeOutCount = 0;
            indexSuccess(true);
            Log.e( logTag, "Successful index" );
            dbHelper.deleteUploadedIndices();
          }else{
            timeOutCount++;
            indexSuccess(false);
            Log.e( logTag, "Failed to index data." );
          }
        }

        if( timeOutCount > 10 ){
          stopUploading();
        }

      }
    dbHelper.close();
    working = false;
  }

  /** */
  void indexSuccess(boolean result) {
    uploadSuccess = result;
    serviceManager.indexSuccess( uploadSuccess );
  }

  void mappingSuccess( boolean result ){
    mapSuccess = result;
  }

  /**
   Extract config information from sharedPreferences.
   Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
   */
  private URL updateUrl( String verb ) {

    String esHost = sharedPreferences.getString("host", "localhost");
    String esPort = sharedPreferences.getString("port", "9200");
    esIndex = sharedPreferences.getString("index", "test_index");
    esType = sharedPreferences.getString("type", "esd");


    // Tag the current date stamp on the index name if set in preferences
    // Thanks GlenRSmith for this idea
    if (sharedPreferences.getBoolean("index_date", false)) {
      Date logDate = new Date(System.currentTimeMillis());
      SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
      String dateString = logDateFormat.format(logDate);
      esIndex = esIndex + "-" + dateString;
    }

    if( verb.equals("POST") ){
      try {
        return new URL( String.format("%s:%s/%s/%s/_bulk", esHost, esPort, esIndex, esType) );
      } catch (MalformedURLException malformedUrlEx) {
        Log.e(logTag, "Failed to update URLs.");
      }
    }else if( verb.equals("MAP") ){
      try {
        return new URL( String.format("%s:%s/%s", esHost, esPort, esIndex) );
      } catch (MalformedURLException malformedUrlEx) {
        Log.e(logTag, "Failed to update URLs.");
      }
    }
    return null;
  }

  private void updateAuthentication(){
    // Update ElasticMapper & ElasticSearchIndexer username/password.
    String esUsername = sharedPreferences.getString("user", "");
    String esPassword = sharedPreferences.getString("pass", "");

    // X-Pack security credentials.
    if (esUsername.length() > 0 && esPassword.length() > 0) {
      esIndexer.esUsername = elasticMapper.esUsername = esUsername;
      esIndexer.esPassword = elasticMapper.esPassword = esPassword;
    }
  }


  private boolean uploadIndex(){

    esIndexer.uploadString = dbHelper.getBulkString(esIndex, esType);

    // If nextString has data.
    if( esIndexer.uploadString != null ) {
      //Log.e( logTag, "Uploading this string: \n" + esIndexer.uploadString );
      indexerThread.start();
      try{
        indexerThread.join();
      }catch(InterruptedException interruptEx ){
        interruptEx.printStackTrace();
      }
    }
    globalUploadTimer = System.currentTimeMillis();
    Log.e(logTag, "Upload success: " + uploadSuccess );
    return uploadSuccess;
  }





}
