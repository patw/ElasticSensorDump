package ca.dungeons.sensordump;

import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.BaseAdapter;

import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.common.api.CommonStatusCodes;

public class Fragment_Preference extends PreferenceFragment {

    private static final String TAG = "Preference_Frag";

    static final int QR_REQUEST_CODE = 1232131213;
    private SharedPreferences sharedPreferences;


    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences( this.getContext() );
        addPreferencesFromResource(R.xml.preferences);
        setupQRButton();
    }

    private void setupQRButton(){
        Preference qrPreference = this.getPreferenceManager().findPreference( "qr_code" );
        qrPreference.setOnPreferenceClickListener( new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent qr_Intent = new Intent( getContext(), QR_Activity.class );
                startActivityForResult( qr_Intent, QR_REQUEST_CODE );
                return false;
            }
        });

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        Log.e( TAG, "Received results from QR reader." );
        if( requestCode == QR_REQUEST_CODE ){

            if( resultCode == CommonStatusCodes.SUCCESS ){
                Log.e( TAG, "Received SUCCESS CODE" );
                if( data != null ){
                    Log.e( TAG, "Intent is NOT NULL" );
                    String hostString = data.getStringExtra("hostString" );
                    if( ! hostString.equals("") ){
                        sharedPreferences.edit().putString( "host", hostString ).apply();
                        onCreate( this.getArguments() );
                    }
                }else{
                    Log.e( TAG, "Supplied intent is null !!" );
                }

            }

        }
    }

    void updatePreferenceScreen(){

        BaseAdapter screenAdapter = (BaseAdapter) this.getPreferenceScreen().getRootAdapter();
        screenAdapter.notifyDataSetChanged();

    }



}
