package ca.dungeons.sensordump;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;

/** */
public class SettingsActivity extends PreferenceActivity {


  /** */
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    checkValues();
    getFragmentManager().beginTransaction().replace(android.R.id.content, new Fragment_Preference()).commit();

  }


  /** */
  private void checkValues() {
    SharedPreferences sharedPrefs =
            PreferenceManager.getDefaultSharedPreferences(getBaseContext());

    String es_host = sharedPrefs.getString("host", "localhost");
    String es_port = sharedPrefs.getString("port", "9200");
    String es_index = sharedPrefs.getString("index", "sensor_dump");
    String es_type = sharedPrefs.getString("type", "phone_data");
    String current_values = "http://" + es_host + ":" + es_port + "/"
            + es_index + "/" + es_type;

    Log.v("Preferences", current_values);
  }


}

