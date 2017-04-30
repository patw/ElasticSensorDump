# Elastic Sensor Dump

Dump sensor and GPS data from Android devices into Elastic Search.  Visualize with Kibana.

The application will enumerate all sensors your phone has on-board, and begin to dump this data in a schemaless way to
the Elastic Search server you have configured.

Project can be compiled and run in a recent version of Android Studio

### As of version 1.5.1 this app now requires Elasticsearch 5.x

## Be sure to import the visualization and dashboard objects in the Kibana directory:
### Management -> Saved Objects -> Import

## Privacy Policy

Elastic Sensor Dump activates the GPS, Sensors and Microphone on your phone/tablet to collect metric data.
 None of this data is stored outside of the Elasticsearch server *you* configure.  The microphone is only
   used to collect aggregate volume level and aggregate frequency.  The app does not record and store any
    actual audio clips.