package ca.dungeons.sensordump;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import static ca.dungeons.sensordump.R.layout.activity_data;


public class DataActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(activity_data);

        //button 1
        //
            //text 1 LATITUDE
            final TextView ButtonType1 = (TextView) findViewById(R.id.ButtonType1);
            ButtonType1.setText( "Latitude" );

            // -- Data Type TBD
            final Button dataButton1 = (Button) findViewById(R.id.dataButton1);
            dataButton1.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    //startActivity(settingsIntent);
                    // start the called activity
                    // use activity template & bundles to transfer data
                }
        });
        //
        // end button1

        //button 2
        //
            //text 2  Longitude
            final TextView ButtonType2 = (TextView) findViewById(R.id.ButtonType2);
            ButtonType2.setText( "Longitude" );

            // -- Data Type TBD
            final Button dataButton2 = (Button) findViewById(R.id.dataButton2);
            dataButton2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    //startActivity(settingsIntent);
                    // start the called activity
                    // use activity template & bundles to transfer data
                }
            });
        //
        // end button2

        //button 3
        //
            //text 3 Altitude above sea level in meters
            final TextView ButtonType3 = (TextView) findViewById(R.id.ButtonType3);
            ButtonType3.setText( "Altitude" );

            // -- Data Type TBD
            final Button dataButton3 = (Button) findViewById(R.id.dataButton3);
            dataButton3.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    //startActivity(settingsIntent);
                    // start the called activity
                    // use activity template & bundles to transfer data
                }
            });
        //
        // end button3


        //button 4
        //
            //text 4  Cardinal Orientation
            final TextView ButtonType4 = (TextView) findViewById(R.id.ButtonType4);
            ButtonType4.setText( "Cardinal Orientation" );

            // -- Data Type TBD
            final Button dataButton4 = (Button) findViewById(R.id.dataButton4);

            dataButton4.setText("");
            dataButton4.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    //startActivity(settingsIntent);
                    // start the called activity
                    // use activity template & bundles to transfer data
                }
            });
        //
        // end button4



        // button 5
        //
            //text 5  (double) Time in hours: 1.50 hours
            final TextView ButtonType5 = (TextView) findViewById(R.id.ButtonType5);
            ButtonType5.setText("Record Time");

            // -- Data Type TBD
            final Button dataButton5 = (Button) findViewById(R.id.dataButton5);
                                //      ms -> seconds -> minutes - > hours
            double recordTime = (((( 50000 ) / 1000 ) / 60 ) / 60 );
            dataButton5.setText( ""+recordTime+"" );
            dataButton5.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    //startActivity(settingsIntent);
                    // start the called activity
                    // use activity template & bundles to transfer data
                }
            });
        //
        // end button5


        // button 6
        //
            //text 6  Data Readings (int)
            final TextView ButtonType6 = (TextView) findViewById(R.id.ButtonType6);
            ButtonType6.setText("Data Readings");

            // -- Data Type TBD
            final Button dataButton6 = (Button) findViewById(R.id.dataButton6);
            int DataReadings = 5000; ///////////savedInstanceState.getInt(  )
            dataButton6.setText(""+ DataReadings +"");
            dataButton6.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    //startActivity(settingsIntent);
                    // start the called activity
                    // use activity template & bundles to transfer data
                }
            });
        //
        // end button6
    }

}
